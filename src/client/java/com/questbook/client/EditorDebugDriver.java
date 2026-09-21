package com.questbook.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.questbook.QuestBook;
import com.questbook.client.gui.AdminQuestScreen;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import com.questbook.network.AdminActionPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Development-only driver for the admin editor.
 *
 * <p>GUI automation is impossible here: the client discards synthetic input, so a
 * test cannot click or type at the real window. Instead this drives the screen in
 * process and photographs it with the client's own screenshot path.
 *
 * <p>F9 opens the editor and immediately screenshots it. F10 advances to the next
 * state and screenshots that too, so one pass captures the empty state, a selected
 * quest, the item picker, and the new-quest dialog.
 *
 * <p>Debug scaffolding, not shipped behaviour: delete this file (and its
 * registration in {@link QuestBookClient}) when the editor stops moving.
 */
public final class EditorDebugDriver {
	/** Where the shots land, relative to the game directory. */
	private static final String OUT_DIR = "editor-shots";

	private static KeyMapping snap;
	private static KeyMapping next;

	/** How many advance-steps have run; drives which state is forced next. */
	private static int step = 0;
	/** Frames to wait before photographing, so the screen is actually drawn. */
	private static int pendingFrames = -1;
	/** The screen this driver opened. Minecraft#screen is not public in 26.2. */
	private static AdminQuestScreen current;

	/**
	 * Self-driving run: ticks down so the whole capture sequence happens with no
	 * input at all. Synthetic keyboard events are discarded by the client, so a
	 * keybind-driven harness cannot be started from outside the process.
	 */
	private static int autoMode = -1;
	/** Last step the auto run should force. */
	private static final int AUTO_LAST = 6;

	private EditorDebugDriver() {
	}

	public static void register() {
		// Development only. This opens a GUI and writes PNGs into the game directory,
		// which must never happen on a player's machine, so it stays inert unless the
		// mod is running from a dev environment.
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}

		KeyMapping.Category category =
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(QuestBook.MOD_ID, "debug"));

		snap = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + QuestBook.MOD_ID + ".debug_shot",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));

		next = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + QuestBook.MOD_ID + ".debug_next",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category));

		ClientTickEvents.END_CLIENT_TICK.register(EditorDebugDriver::onTick);
	}

	/** Ticks to wait after login before opening the editor, so syncs have landed. */
	private static int startupDelay = 400;

	private static void onTick(Minecraft client) {
		if (client.player == null) {
			return;
		}

		// Finish any outstanding screenshot copy before anything else: the grab is
		// asynchronous and the file only appears a second or more after the request.
		if (pendingCopy != null) {
			collectShot(client);
			return;
		}

		// Count down to the auto run, then hand over to it.
		if (startupDelay > 0 && --startupDelay == 0) {
			autoMode = 0;
			return;
		}

		// Self-driving capture: no input needed, starts a few seconds after login.
		if (autoMode >= 0) {
			autoStep(client);
			return;
		}

		while (snap.consumeClick()) {
			step = 0;
			current = new AdminQuestScreen();
			client.setScreenAndShow(current);
			arm(3);
		}

		while (next.consumeClick()) {
			step++;
			applyStep();
			arm(3);
		}

		if (pendingFrames > 0 && --pendingFrames == 0) {
			shoot(client);
		}
	}

	/** Waits a few frames so the screen has been drawn before it is photographed. */
	private static void arm(int frames) {
		pendingFrames = frames;
	}

	/** Grace ticks between forcing a state and photographing it. */
	private static final int AUTO_SETTLE = 12;

	/**
	 * Auto run as an explicit two-phase loop: force state N, wait {@link #AUTO_SETTLE}
	 * ticks, shoot it, then move to N+1. Runs exactly once, so a long session cannot
	 * spam screenshots.
	 */
	private static void autoStep(Minecraft client) {
		if (autoMode == 0) {
			// Don't open until the server's admin data has actually arrived; an empty
			// editor would photograph a state users never see.
			if (ClientAdminState.quests().isEmpty()) {
				if (startupDelay > 0) {
					startupDelay--;
					return;
				}
				QuestBook.LOGGER.info("Editor capture: no admin data after wait, capturing anyway");
			}
			if (step == 0) {
				// Drop the previous run's images first. Otherwise a failed capture
				// leaves old PNGs in place and they read as the new result.
				clearPreviousShots(client);
			}
			// phase A: force the state for this step, then wait for it to draw
			QuestBook.LOGGER.info("Editor capture: state {} ({} quests)",
					step, ClientAdminState.quests().size());
			if (step == 0) {
				// Mirror the real keybind: open, and ask the server for admin data.
				if (ClientPlayNetworking.canSend(AdminActionPayload.TYPE)) {
					ClientPlayNetworking.send(AdminActionPayload.requestSync());
				}
				current = new AdminQuestScreen();
				client.setScreenAndShow(current);
			} else {
				applyStep();
			}
			autoMode = AUTO_SETTLE;
			return;
		}

		if (--autoMode > 0) {
			return;
		}

		// phase B: the state has been drawn for a while — photograph it
		shoot(client);

		if (step >= AUTO_LAST) {
			QuestBook.LOGGER.info("Editor capture finished after state {}", step);
			autoMode = -1;
			return;
		}

		step++;
		autoMode = 0;
	}

	/** Forces the screen into the state matching the current step. */
	private static void applyStep() {
		if (current == null) {
			QuestBook.LOGGER.warn("Editor capture: no screen to step {}", step);
			return;
		}
		// Any parked pointer belongs to the previous step only, so a hover state cannot
		// leak its tooltip into the next shot.
		current.debugClearHover();
		QuestBook.LOGGER.info("Editor capture: forcing step {} ({})", step, current.debugState());
		switch (step) {
			case 1 -> current.debugSelectFirstQuest();
			case 2 -> current.debugOpenPicker();
			case 3 -> current.debugOpenNewQuestDialog();
			case 4 -> {
				// Back to the picker, with the search field filled: the placeholder
				// shot cannot show whether a typed value lands in the same place.
				current.debugOpenPicker();
				current.debugTypeSearch("dia");
			}
			case 5 -> current.debugOpenEditTask();
			case 6 -> current.debugHoverTab(2);
			default -> current.debugAdvanceFocus();
		}
	}
	private static void shoot(Minecraft client) {
		try {
			Path dir = client.gameDirectory.toPath().resolve(OUT_DIR);
			Files.createDirectories(dir);
			Path shots = client.gameDirectory.toPath().resolve("screenshots");

			// Snapshot what is already there so the file this grab produces can be
			// identified exactly. "Newest file" is unreliable: two states can land in
			// the same second and the copy then picks the previous state's shot.
			Set<Path> before = new HashSet<>();
			if (Files.isDirectory(shots)) {
				try (var s = Files.list(shots)) {
					s.forEach(before::add);
				}
			}

			Screenshot.grab(client, false);

			// The grab is asynchronous — it hands work to the GPU thread and the file
			// appears a second or more later. Polling here blocks the render thread,
			// which is the thread that has to finish writing the file, so the wait is
			// handed to the next ticks instead.
			pendingCopy = before;
			pendingCopyStep = step;
			pendingCopyState = current != null ? current.debugState() : "?";
			copyTries = 120; // ~6s at 20 TPS
		} catch (Exception e) {
			QuestBook.LOGGER.error("Editor screenshot failed", e);
		}
	}

	/** Removes last run's state-N.png files so a failed capture cannot masquerade. */
	private static void clearPreviousShots(Minecraft client) {
		Path dir = client.gameDirectory.toPath().resolve(OUT_DIR);
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().startsWith("state-"))
					.forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (Exception e) {
							QuestBook.LOGGER.warn("Could not delete stale shot {}", p);
						}
					});
			QuestBook.LOGGER.info("Editor capture: cleared previous shots");
		} catch (Exception e) {
			QuestBook.LOGGER.warn("Could not list shot dir", e);
		}
	}

	/** Files already present when a shot was requested; the new one is the grab's. */
	private static Set<Path> pendingCopy;
	/** The step the pending shot belongs to; {@link #step} advances before it lands. */
	private static int pendingCopyStep;
	/** The screen state at shoot time, for the log line that accompanies the copy. */
	private static String pendingCopyState = "?";
	/** Remaining polls for {@link #pendingCopy}. */
	private static int copyTries;
	/** Candidate file and its last-seen size, to detect a finished write. */
	private static Path copyCandidate;
	private static long copyCandidateSize = -1;

	/** Copies the screenshot the last grab produced, once its write has settled. */
	private static void collectShot(Minecraft client) {
		Path dir = client.gameDirectory.toPath().resolve(OUT_DIR);
		Path shots = client.gameDirectory.toPath().resolve("screenshots");
		try {
			// Find the new file once, then wait for its size to stop changing. The
			// GPU thread creates it before writing, so copying on existence alone
			// produces a 0-byte image and copying on "size > 0" can truncate.
			if (copyCandidate == null) {
				if (Files.isDirectory(shots)) {
					try (var s = Files.list(shots)) {
						copyCandidate = s.filter(p -> p.getFileName().toString().endsWith(".png"))
								.filter(p -> !pendingCopy.contains(p))
								.findFirst()
								.orElse(null);
					}
				}
				if (copyCandidate == null) {
					if (--copyTries <= 0) {
						QuestBook.LOGGER.warn("Editor shot state {}: no screenshot appeared", pendingCopyStep);
						pendingCopy = null;
					}
					return;
				}
				copyCandidateSize = -1;
				return;
			}

			long size = Files.size(copyCandidate);
			if (size == 0 || size != copyCandidateSize) {
				copyCandidateSize = size;
				if (--copyTries <= 0) {
					QuestBook.LOGGER.warn("Editor shot state {}: write never settled ({} bytes)",
							pendingCopyStep, size);
					resetShot();
				}
				return;
			}

			Path out = dir.resolve("state-" + pendingCopyStep + ".png");
			Files.copy(copyCandidate, out, StandardCopyOption.REPLACE_EXISTING);
			QuestBook.LOGGER.info("Editor shot state {} -> {} ({} bytes)", pendingCopyStep, out, size);
			QuestBook.LOGGER.info("Editor state {}", pendingCopyState);
			if (current != null) {
				QuestBook.LOGGER.info("Editor geometry {}", current.debugGeometry());
				QuestBook.LOGGER.info("Editor grid {}", current.debugGridState());
				QuestBook.LOGGER.info("Editor search value {:?}", current.debugSearchValue());
			}
			resetShot();
		} catch (Exception e) {
			QuestBook.LOGGER.error("Editor shot copy failed", e);
			resetShot();
		}
	}

	private static void resetShot() {
		pendingCopy = null;
		copyCandidate = null;
		copyCandidateSize = -1;
	}
}
