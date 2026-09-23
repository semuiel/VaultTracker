package ru.vaulttracker;

import java.nio.file.Path;
import java.util.logging.Logger;

public final class GenerateItemEmojis {
    public static void main(String[] args) {TelegramItemIcons.configure(Path.of(args[0]),Logger.getAnonymousLogger());}
}
