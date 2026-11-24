package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
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
        // Parse the actual registered children without a live player's permission context.
        // Execution still belongs to runtime tests; no command callback is invoked here.
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

    private void assertParses(String command) {
        ParseResults<CommandSourceStack> result = dispatcher().parse("maidtasks " + command, null);
        assertFalse(result.getReader().canRead(), command);
        assertTrue(result.getExceptions().isEmpty(), command);
        assertNotNull(result.getContext().getCommand(), command);
    }
}
