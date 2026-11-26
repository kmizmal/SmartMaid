package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MaidTaskCommandTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> registered = new CommandDispatcher<>();
        MaidTaskCommand.register(registered, Commands.LEVEL_ALL);
        // Parse the registered children without a player context in the syntax-only tests.
        CommandDispatcher<CommandSourceStack> parsing = new CommandDispatcher<>();
        CommandNode<CommandSourceStack> root = parsing.register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("maidtasks"));
        registered.getRoot().getChild("maidtasks").getChildren().forEach(root::addChild);
        return parsing;
    }

    @Test
    void retainsEveryExistingSubcommand() {
        Set<String> expected = Set.of("attack", "guard", "feed", "eat", "mine", "farm",
                "build", "collect", "craft", "smelt", "transfer", "chestopen", "chestput",
                "chesttake", "move", "look", "break", "place", "use", "equip", "store",
                "drop", "pickup", "sit", "stop", "cancel", "status");
        Set<String> actual = dispatcher().getRoot().getChild("maidtasks").getChildren()
                .stream().map(CommandNode::getName).collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    @Test
    void parsesRestoredAtomicCommands() {
        for (String command : new String[]{
                "transfer mainhand offhand", "transfer mainhand offhand 64",
                "chestopen ~ ~ ~", "chestput", "chestput 64",
                "chesttake", "chesttake 0", "chesttake 255 64",
                "look ~ ~ ~", "break ~ ~ ~", "place ~ ~ ~", "use ~ ~ ~",
                "equip minecraft:iron_sword", "store"}) {
            assertParses(command);
        }
    }

    @Test
    void parsesDefaultAndOptionalTaskArguments() {
        for (String command : new String[]{
                "attack", "attack 12", "attack 12 pig", "guard", "guard 64",
                "mine", "mine ~ ~ ~", "mine ~ ~ ~ 16", "mine ~ ~ ~ 16 256",
                "farm ~ ~ ~", "farm ~ ~ ~ 16",
                "craft minecraft:stick", "craft minecraft:stick 64",
                "smelt minecraft:iron_ingot", "smelt minecraft:iron_ingot 64"}) {
            assertParses(command);
        }
    }

    @Test
    void rejectsOutOfRangeArguments() {
        for (String command : new String[]{
                "attack 0", "attack 65 pig", "mine ~ ~ ~ 0", "mine ~ ~ ~ 17",
                "mine ~ ~ ~ 4 257", "farm ~ ~ ~ 17",
                "craft minecraft:stick 0", "craft minecraft:stick 65",
                "smelt minecraft:iron_ingot 65", "transfer mainhand offhand 65",
                "chestput 0", "chesttake 256", "chesttake 1 65"}) {
            ParseResults<CommandSourceStack> result = dispatcher().parse("maidtasks " + command, null);
            assertTrue(result.getReader().canRead() || !result.getExceptions().isEmpty(), command);
        }
    }

    @Test
    void checksPermissionBeforeExecutingRestoredTransfer() throws CommandSyntaxException {
        CommandDispatcher<CommandSourceStack> registered = new CommandDispatcher<>();
        MaidTaskCommand.register(registered, Commands.LEVEL_GAMEMASTERS);
        String command = "maidtasks transfer mainhand offhand";

        CommandSyntaxException denied = assertThrows(CommandSyntaxException.class,
                () -> registered.execute(command, sourceWithoutPlayer(PermissionLevel.ALL)));
        assertSame(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), denied.getType());

        // The allowed source reaches the real transfer handler, which returns 0 without a player.
        assertEquals(0, registered.execute(command, sourceWithoutPlayer(PermissionLevel.GAMEMASTERS)));
    }

    private static CommandSourceStack sourceWithoutPlayer(PermissionLevel level) {
        try {
            // 26.2 exposes the legacy 9-arg constructor; 26.3 replaced it with a 7-arg
            // constructor using Component/MinecraftServer. Keep the shared JUnit source
            // compilable against both versions through reflection.
            return CommandSourceStack.class.getConstructor(
                    CommandSource.class, Vec3.class, Vec2.class, ServerLevel.class,
                    net.minecraft.server.permissions.PermissionSet.class, String.class,
                    Component.class, MinecraftServer.class, Entity.class
            ).newInstance(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                    LevelBasedPermissionSet.forLevel(level), "test", Component.literal("test"), null, null);
        } catch (NoSuchMethodException ignored) {
            try {
                return CommandSourceStack.class.getConstructor(
                        CommandSource.class, Vec3.class, Vec2.class, ServerLevel.class,
                        net.minecraft.server.permissions.PermissionSet.class,
                        Component.class, MinecraftServer.class
                ).newInstance(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                        LevelBasedPermissionSet.forLevel(level), Component.literal("test"), null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("无法构造 CommandSourceStack 测试源", e);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造 CommandSourceStack 测试源", e);
        }
    }

    private void assertParses(String command) {
        ParseResults<CommandSourceStack> result = dispatcher().parse("maidtasks " + command, null);
        assertFalse(result.getReader().canRead(), command);
        assertTrue(result.getExceptions().isEmpty(), command);
        assertNotNull(result.getContext().getCommand(), command);
    }
}
