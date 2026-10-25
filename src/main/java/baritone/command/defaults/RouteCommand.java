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
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BetterBlockPos;
import baritone.process.RouteProcess;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class RouteCommand extends Command {

    private static final List<String> ACTIONS = Arrays.asList(
            "add", "undo", "clear", "list", "record", "start", "stop", "save", "load", "saved"
    );

    public RouteCommand(IBaritone baritone) {
        super(baritone, "route");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        RouteProcess route = ((Baritone) baritone).getRouteProcess();
        String action = args.hasAny() ? args.getString().toLowerCase() : "list";
        try {
            switch (action) {
                case "add": {
                    args.requireMax(3);
                    BetterBlockPos origin = ctx.playerFeet();
                    BetterBlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, origin) : origin;
                    route.add(pos);
                    logDirect("Точка " + route.getPoints().size() + ": " + pos.x + " " + pos.y + " " + pos.z);
                    break;
                }
                case "undo": {
                    args.requireMax(0);
                    BetterBlockPos p = route.undo();
                    logDirect(p == null ? "Маршрут пуст" : "Убрал " + p.x + " " + p.y + " " + p.z);
                    break;
                }
                case "clear": {
                    args.requireMax(0);
                    route.clear();
                    logDirect("Маршрут очищен");
                    break;
                }
                case "list": {
                    args.requireMax(0);
                    List<BetterBlockPos> pts = route.getPoints();
                    if (pts.isEmpty()) {
                        logDirect("Маршрут пуст");
                        break;
                    }
                    logDirect("Точек: " + pts.size());
                    for (int i = 0; i < pts.size(); i++) {
                        BetterBlockPos p = pts.get(i);
                        logDirect((i + 1) + ": " + p.x + " " + p.y + " " + p.z);
                    }
                    break;
                }
                case "record": {
                    args.requireMax(0);
                    if (route.isRecording()) {
                        route.stopRecording();
                        logDirect("Запись остановлена, точек: " + route.getPoints().size());
                    } else {
                        route.startRecording();
                        logDirect("Запись пошла, иди по нужному пути. Ещё раз #route record остановит");
                    }
                    break;
                }
                case "start": {
                    args.requireMax(1);
                    boolean loop = args.hasAny() && args.getString().equalsIgnoreCase("loop");
                    if (!route.start(loop)) {
                        logDirect("Маршрут пуст");
                    } else {
                        logDirect("Иду по маршруту" + (loop ? " по кругу" : ""));
                    }
                    break;
                }
                case "stop": {
                    args.requireMax(0);
                    route.stop();
                    route.stopRecording();
                    logDirect("Остановлено");
                    break;
                }
                case "save": {
                    args.requireExactly(1);
                    String saved = route.save(args.getString());
                    logDirect(saved == null ? "Нечего сохранять или кривое имя" : "Сохранено: " + saved);
                    break;
                }
                case "load": {
                    args.requireExactly(1);
                    int n = route.load(args.getString());
                    logDirect(n < 0 ? "Нет такого маршрута" : "Загружено точек: " + n);
                    break;
                }
                case "saved": {
                    args.requireMax(0);
                    List<String> names = route.savedNames();
                    logDirect(names.isEmpty() ? "Сохранённых нет" : String.join(", ", names));
                    break;
                }
                default:
                    logDirect("Не знаю действия '" + action + "'. Смотри #help route");
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
        return "Pass a user-defined list of points in order";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The route command makes Baritone walk through a list of points you set or recorded.",
                "Between two points Baritone still chooses its own path, so more points mean closer to your line.",
                "",
                "Usage:",
                "> route add - Add the point where you stand",
                "> route add <x> <y> <z> - Add a point (~ works)",
                "> route record - Start recording while you walk, run again to stop",
                "> route undo - Remove the last point",
                "> route list - Show the points",
                "> route clear - Remove all points",
                "> route start [loop] - Walk the route, optionally in a loop",
                "> route stop - Stop walking or recording",
                "> route save <name> - Save to the baritone/routes folder",
                "> route load <name> - Load a saved route",
                "> route saved - List saved routes"
        );
    }
}
