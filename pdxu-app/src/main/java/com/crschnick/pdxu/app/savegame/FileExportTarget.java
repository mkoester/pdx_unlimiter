package com.crschnick.pdxu.app.savegame;

import com.crschnick.pdxu.app.core.window.AppDialog;
import com.crschnick.pdxu.app.info.SavegameInfo;
import com.crschnick.pdxu.app.info.ck2.Ck2SavegameInfo;
import com.crschnick.pdxu.app.info.stellaris.StellarisSavegameInfo;
import com.crschnick.pdxu.app.installation.Game;
import com.crschnick.pdxu.app.util.FileSystemHelper;
import com.crschnick.pdxu.model.ck2.Ck2Tag;
import com.crschnick.pdxu.model.stellaris.StellarisTag;

import javafx.beans.property.SimpleStringProperty;

import org.apache.commons.io.FilenameUtils;

import java.nio.file.Path;
import java.util.function.Function;

public abstract class FileExportTarget<T, I extends SavegameInfo<T>> {

    protected final Path targetDir;
    protected final boolean includeEntryName;
    protected SavegameStorage<T, I> storage;
    protected SavegameEntry<T, I> entry;

    FileExportTarget(
            Path targetDir, boolean includeEntryName, SavegameStorage<T, I> storage, SavegameEntry<T, I> entry) {
        this.targetDir = targetDir;
        this.includeEntryName = includeEntryName;
        this.storage = storage;
        this.entry = entry;
    }

    public static <T, I extends SavegameInfo<T>> FileExportTarget<T, I> createExportTarget(SavegameEntry<T, I> entry) {
        return SavegameContext.mapSavegame(entry, ctx -> {
            return createExportTarget(ctx.getInstallation().getSavegamesExportDir(), false, true, entry);
        });
    }

    @SuppressWarnings("unchecked")
    public static <T, I extends SavegameInfo<T>> FileExportTarget<T, I> createExportTarget(
            Path dir, boolean includeEntryName, boolean askForFileName, SavegameEntry<T, I> entry) {
        return SavegameContext.mapSavegame(entry, ctx -> {
            if (SavegameStorage.get(Game.STELLARIS).equals(ctx.getStorage())) {
                return (FileExportTarget<T, I>) new StellarisExportTarget(
                        dir,
                        includeEntryName,
                        askForFileName,
                        (SavegameStorage<StellarisTag, StellarisSavegameInfo>) ctx.getStorage(),
                        (SavegameEntry<StellarisTag, StellarisSavegameInfo>) entry);
            } else if (SavegameStorage.get(Game.CK2).equals(ctx.getStorage())
                    && entry.getInfo().getData().isIronman()) {
                return (FileExportTarget<T, I>) new FixedNameTarget<>(
                        dir,
                        includeEntryName,
                        (SavegameStorage<Ck2Tag, Ck2SavegameInfo>) ctx.getStorage(),
                        (SavegameEntry<Ck2Tag, Ck2SavegameInfo>) entry,
                        e -> "../" + e.getInfo().getData().ck2().getIronmanSaveName());
            } else {
                return new StandardExportTarget<>(dir, includeEntryName, ctx.getStorage(), entry);
            }
        });
    }

    public abstract Path export() throws Exception;

    public static class StandardExportTarget<T, I extends SavegameInfo<T>> extends FileExportTarget<T, I> {

        public StandardExportTarget(
                Path savegameDir, boolean includeEntryName, SavegameStorage<T, I> storage, SavegameEntry<T, I> entry) {
            super(savegameDir, includeEntryName, storage, entry);
        }

        private Path getOutputFile() {
            var customId = storage.getCustomCampaignId(entry);
            // Only try to add id suffix in case for ironman or binary ones
            var suffix = entry.getInfo() != null
                            && (entry.getInfo().getData().isIronman()
                                    || entry.getInfo().getData().isBinary())
                    ? customId.map(u -> " (" + u + ")").orElse(null)
                    : null;
            var baseName = storage.getValidOutputFileName(entry, includeEntryName, suffix);
            return targetDir.resolve(baseName);
        }

        @Override
        public Path export() throws Exception {
            var out = getOutputFile();
            storage.copySavegameTo(entry, out);
            return out;
        }
    }

    public static class FixedNameTarget<T, I extends SavegameInfo<T>> extends FileExportTarget<T, I> {

        private final Function<SavegameEntry<T, I>, String> nameFunction;

        public FixedNameTarget(
                Path savegameDir,
                boolean includeEntryName,
                SavegameStorage<T, I> storage,
                SavegameEntry<T, I> entry,
                Function<SavegameEntry<T, I>, String> nameFunction) {
            super(savegameDir, includeEntryName, storage, entry);
            this.nameFunction = nameFunction;
        }

        private Path getOutputFile() {
            return targetDir.resolve(nameFunction.apply(entry));
        }

        @Override
        public Path export() throws Exception {
            var out = getOutputFile();
            storage.copySavegameTo(entry, out);
            return out;
        }
    }

    public static class StellarisExportTarget extends FileExportTarget<StellarisTag, StellarisSavegameInfo> {

        private final boolean askForFileName;

        public StellarisExportTarget(
                Path savegameDir,
                boolean includeEntryName,
                boolean askForFileName,
                SavegameStorage<StellarisTag, StellarisSavegameInfo> storage,
                SavegameEntry<StellarisTag, StellarisSavegameInfo> entry) {
            super(savegameDir, includeEntryName, storage, entry);
            this.askForFileName = askForFileName;
        }

        @Override
        public Path export() throws Exception {
            var baseName = FilenameUtils.getBaseName(storage.getValidOutputFileName(entry, includeEntryName, null)
                    .toString());
            var customId = storage.getSavegameCampaign(entry).getUuid();
            var suffix = " (" + customId + ")";

            Path file;
            Path dir = targetDir.resolve(baseName + suffix);
            if (entry.getInfo().getData().isIronman()) {
                file = dir.resolve("ironman.sav");
            } else {
                file = dir.resolve(getFileName());
            }
            storage.copySavegameTo(entry, file);
            return file;
        }

        private String getFileName() {
            var originalFileName = entry.getOriginalFileName();
            if (originalFileName == null) {
                return entry.getDate() + ".sav";
            }

            var original = Path.of(originalFileName).getFileName().toString();
            // The name set at import (extracted suffix or date) is the baseline, only a manual rename changes it
            var renamed = entry.getImportedName() != null
                    && !entry.getName().equals(entry.getImportedName());
            if (!renamed) {
                return original;
            }

            // Asked once per rename
            if (entry.getName().equals(entry.getFileNameChoiceName())) {
                return entry.getChosenFileName();
            }

            if (!askForFileName) {
                return original;
            }

            var name = FileSystemHelper.getFileSystemCompatibleName(entry.getName());
            var proposed = entry.getDate() + (name.isEmpty() ? "" : "_" + name) + ".sav";
            if (proposed.equals(original)) {
                return original;
            }

            boolean useEditedName = AppDialog.choose(
                    "savegameFilenameChoiceTitle",
                    new SimpleStringProperty(original + " → " + proposed),
                    "useOriginalFilename",
                    "useEditedSaveName");
            var chosen = useEditedName ? proposed : original;
            entry.setFileNameChoice(entry.getName(), chosen);
            return chosen;
        }
    }
}
