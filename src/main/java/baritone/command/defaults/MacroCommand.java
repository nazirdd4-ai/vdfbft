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
package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.behavior.MacroBehavior;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MacroCommand extends Command {

    private static final List<String> ACTIONS = Arrays.asList(
            "rec", "play", "stop", "info", "save", "load", "saved"
    );

    public MacroCommand(IBaritone baritone) {
        super(baritone, "macro");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        MacroBehavior macro = ((Baritone) baritone).getMacroBehavior();
        String action = args.hasAny() ? args.getString().toLowerCase() : "info";
        try {
            switch (action) {
                case "rec": {
                    args.requireMax(0);
                    if (macro.isRecording()) {
                        macro.stopRecording();
                        logDirect("Запись остановлена, тиков: " + macro.size());
                    } else {
                        macro.startRecording();
                        logDirect("Запись пошла: ходи, прыгай, бей, ставь. Ещё раз #macro rec остановит");
                    }
                    break;
                }
                case "play": {
                    args.requireMax(2);
                    boolean loop = false;
                    boolean here = false;
                    while (args.hasAny()) {
                        String flag = args.getString().toLowerCase();
                        if (flag.equals("loop")) {
                            loop = true;
                        } else if (flag.equals("here")) {
                            here = true;
                        } else {
                            logDirect("Не знаю флаг '" + flag + "', есть loop и here");
                            return;
                        }
                    }
                    if (!macro.play(loop, here)) {
                        logDirect("Нечего повторять, сначала #macro rec или #macro load");
                    } else {
                        logDirect(here ? "Повторяю отсюда" : "Иду на старт и повторяю");
                    }
                    break;
                }
                case "stop": {
                    args.requireMax(0);
                    macro.stopRecording();
                    macro.stop();
                    logDirect("Остановлено");
                    break;
                }
                case "info": {
                    args.requireMax(0);
                    logDirect("Тиков в записи: " + macro.size() + (macro.isRecording() ? " (идёт запись)" : "") + (macro.isPlaying() ? " (идёт повтор)" : ""));
                    break;
                }
                case "save": {
                    args.requireExactly(1);
                    String saved = macro.save(args.getString());
                    logDirect(saved == null ? "Нечего сохранять или кривое имя" : "Сохранено: " + saved);
                    break;
                }
                case "load": {
                    args.requireExactly(1);
                    int n = macro.load(args.getString());
                    logDirect(n < 0 ? "Нет такой записи" : "Загружено тиков: " + n);
                    break;
                }
                case "saved": {
                    args.requireMax(0);
                    List<String> names = macro.savedNames();
                    logDirect(names.isEmpty() ? "Сохранённых нет" : String.join(", ", names));
                    break;
                }
                default:
                    logDirect("Не знаю действия '" + action + "'. Смотри #help macro");
            }
        } catch (IOException | RuntimeException e) {
            logDirect("Ошибка: " + e.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase();
            return ACTIONS.stream().filter(a -> a.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Record and replay your inputs tick by tick";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The macro command records your keys, look angles and hotbar slot every tick and replays them.",
                "Playback first paths to the recorded start block, then repeats the inputs.",
                "Jumps, breaking and placing blocks are part of the recording. Inventory clicks are not.",
                "",
                "Usage:",
                "> macro rec - Start recording, run again to stop",
                "> macro play [loop] [here] - Replay. here skips going to the start, loop repeats forever",
                "> macro stop - Stop recording or playback",
                "> macro info - Show how many ticks are recorded",
                "> macro save <name> - Save to the baritone/macros folder",
                "> macro load <name> - Load a saved recording",
                "> macro saved - List saved recordings"
        );
    }
}
