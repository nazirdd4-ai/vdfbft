/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.Helper;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Records the player's inputs every tick (movement keys, jump, sneak, sprint, attack, use, look angles, hotbar slot)
 * and plays them back tick by tick. Playback starts by pathing to the recorded start block.
 * <p>
 * Registered before the InputOverrideHandler so the forced inputs are visible to it in the same tick.
 */
public class MacroBehavior extends Behavior {

    private enum State {
        IDLE,
        RECORDING,
        TO_START,
        PLAYING
    }

    private record Frame(float yaw, float pitch, int keys, int slot) {
    }

    /**
     * How far from the recorded start block we may be when playback begins
     */
    private static final double START_TOLERANCE = 3;

    private final List<Frame> frames = new ArrayList<>();
    private State state = State.IDLE;
    private double startX, startY, startZ;
    private boolean hasStart;
    private boolean loop;
    private int index;

    public MacroBehavior(Baritone baritone) {
        super(baritone);
    }

    private static void say(String msg) {
        Helper.HELPER.logDirect(msg);
    }

    // ---- state ----

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    public boolean isPlaying() {
        return state == State.TO_START || state == State.PLAYING;
    }

    public int size() {
        return frames.size();
    }

    // ---- recording ----

    public void startRecording() {
        stop();
        LocalPlayer p = ctx.player();
        frames.clear();
        startX = p.getX();
        startY = p.getY();
        startZ = p.getZ();
        hasStart = true;
        state = State.RECORDING;
    }

    public void stopRecording() {
        if (state == State.RECORDING) {
            state = State.IDLE;
        }
    }

    private boolean down(Input in) {
        Options o = ctx.minecraft().options;
        return switch (in) {
            case MOVE_FORWARD -> o.keyUp.isDown();
            case MOVE_BACK -> o.keyDown.isDown();
            case MOVE_LEFT -> o.keyLeft.isDown();
            case MOVE_RIGHT -> o.keyRight.isDown();
            case CLICK_LEFT -> o.keyAttack.isDown();
            case CLICK_RIGHT -> o.keyUse.isDown();
            case JUMP -> o.keyJump.isDown();
            case SNEAK -> o.keyShift.isDown();
            case SPRINT -> o.keySprint.isDown();
        };
    }

    private void record() {
        LocalPlayer p = ctx.player();
        int keys = 0;
        // with a screen open (chat, inventory) keys do not act on the player, but the tick still counts for timing
        if (ctx.minecraft().screen == null) {
            for (Input in : Input.values()) {
                if (down(in)) {
                    keys |= 1 << in.ordinal();
                }
            }
        }
        frames.add(new Frame(p.getYRot(), p.getXRot(), keys, p.getInventory().getSelectedSlot()));
    }

    // ---- playback ----

    public boolean play(boolean loop, boolean here) {
        if (frames.isEmpty() || !hasStart) {
            return false;
        }
        stop();
        this.loop = loop;
        if (here) {
            beginPlay();
        } else {
            goToStart();
        }
        return true;
    }

    private void goToStart() {
        state = State.TO_START;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock((int) Math.floor(startX), (int) Math.floor(startY), (int) Math.floor(startZ)));
    }

    private void beginPlay() {
        double dx = ctx.player().getX() - startX;
        double dy = ctx.player().getY() - startY;
        double dz = ctx.player().getZ() - startZ;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > START_TOLERANCE) {
            say("Не дошёл до точки старта, повтор отменён");
            stop();
            return;
        }
        baritone.getPathingControlManager().cancelEverything();
        index = 0;
        state = State.PLAYING;
    }

    private void playTick() {
        if (index >= frames.size()) {
            if (loop) {
                releaseKeys();
                goToStart();
            } else {
                say("Повтор закончен");
                stop();
            }
            return;
        }
        Frame f = frames.get(index++);
        LocalPlayer p = ctx.player();
        p.setYRot(f.yaw());
        p.setXRot(f.pitch());
        if (p.getInventory().getSelectedSlot() != f.slot()) {
            p.getInventory().setSelectedSlot(f.slot());
        }
        IInputOverrideHandler h = baritone.getInputOverrideHandler();
        for (Input in : Input.values()) {
            h.setInputForceState(in, (f.keys() & (1 << in.ordinal())) != 0);
        }
    }

    private void releaseKeys() {
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    public void stop() {
        boolean wasPlaying = isPlaying();
        state = State.IDLE;
        loop = false;
        if (wasPlaying) {
            releaseKeys();
            baritone.getPathingControlManager().cancelEverything();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            if (state != State.IDLE) {
                state = State.IDLE;
                loop = false;
            }
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            return;
        }
        switch (state) {
            case RECORDING -> record();
            case TO_START -> {
                if (!baritone.getCustomGoalProcess().isActive()) {
                    beginPlay();
                }
            }
            case PLAYING -> playTick();
            default -> {
            }
        }
    }

    // ---- files ----

    private Path macrosDir() throws IOException {
        Path dir = baritone.getDirectory().resolve("macros");
        Files.createDirectories(dir);
        return dir;
    }

    private static String clean(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    /**
     * @return the saved name, or null if there is nothing to save or the name is empty after cleaning
     */
    public String save(String rawName) throws IOException {
        String name = clean(rawName);
        if (name.isEmpty() || frames.isEmpty() || !hasStart) {
            return null;
        }
        List<String> lines = new ArrayList<>(frames.size() + 1);
        lines.add(startX + " " + startY + " " + startZ);
        for (Frame f : frames) {
            lines.add(f.yaw() + " " + f.pitch() + " " + f.keys() + " " + f.slot());
        }
        Files.write(macrosDir().resolve(name + ".txt"), lines);
        return name;
    }

    /**
     * @return the number of loaded ticks, or -1 if the file does not exist
     */
    public int load(String rawName) throws IOException {
        Path file = macrosDir().resolve(clean(rawName) + ".txt");
        if (!Files.exists(file)) {
            return -1;
        }
        List<String> lines = Files.readAllLines(file);
        String[] st = lines.get(0).trim().split("\\s+");
        double sx = Double.parseDouble(st[0]);
        double sy = Double.parseDouble(st[1]);
        double sz = Double.parseDouble(st[2]);
        List<Frame> loaded = new ArrayList<>(lines.size() - 1);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] t = line.split("\\s+");
            loaded.add(new Frame(Float.parseFloat(t[0]), Float.parseFloat(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3])));
        }
        stop();
        frames.clear();
        frames.addAll(loaded);
        startX = sx;
        startY = sy;
        startZ = sz;
        hasStart = true;
        return loaded.size();
    }

    public List<String> savedNames() throws IOException {
        try (Stream<Path> s = Files.list(macrosDir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".txt"))
                    .map(f -> f.substring(0, f.length() - 4))
                    .sorted()
                    .toList();
        }
    }
}
