package com.crschnick.pdxu.app.savegame;

import com.crschnick.pdxu.app.info.SavegameInfo;
import com.crschnick.pdxu.app.core.window.AppDialog;
import com.crschnick.pdxu.app.info.ck2.Ck2SavegameInfo;
import com.crschnick.pdxu.app.info.stellaris.StellarisSavegameInfo;
import com.crschnick.pdxu.app.installation.Game;
import com.crschnick.pdxu.app.prefs.AppPrefs;
import com.crschnick.pdxu.app.util.FileSystemHelper;
import com.crschnick.pdxu.model.ck2.Ck2Tag;
import com.crschnick.pdxu.model.stellaris.StellarisTag;

import org.apache.commons.io.FilenameUtils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import javafx.beans.property.SimpleStringProperty;

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
            return createExportTarget(ctx.getInstallation().getSavegamesExportDir(), false, entry);
        });
    }

    @SuppressWarnings("unchecked")
    public static <T, I extends SavegameInfo<T>> FileExportTarget<T, I> createExportTarget(
            Path dir, boolean includeEntryName, SavegameEntry<T, I> entry) {
        return SavegameContext.mapSavegame(entry, ctx -> {
            if (SavegameStorage.get(Game.STELLARIS).equals(ctx.getStorage())) {
                return (FileExportTarget<T, I>) new StellarisExportTarget(
                        dir,
                        includeEntryName,
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

        public StellarisExportTarget(
                Path savegameDir,
                boolean includeEntryName,
                SavegameStorage<StellarisTag, StellarisSavegameInfo> storage,
                SavegameEntry<StellarisTag, StellarisSavegameInfo> entry) {
            super(savegameDir, includeEntryName, storage, entry);
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
                var originalFileName = entry.getOriginalFileName();
                var fileName = Path.of(entry.getDate() + ".sav");
                var editedName = getCurrentOptionalName();
                if (originalFileName != null
                        && !Objects.equals(editedName, entry.getOriginalOptionalName())) {
                    var safeName = editedName != null
                            ? "_" + FileSystemHelper.getFileSystemCompatibleName(editedName)
                            : "";
                    var proposedFileName = entry.getDate() + safeName + ".sav";
                    boolean useEditedName = AppDialog.choose(
                            "savegameFilenameChoiceTitle",
                            new SimpleStringProperty(originalFileName + " → " + proposedFileName),
                            "useOriginalFilename",
                            "useEditedSaveName");
                    fileName = Path.of(useEditedName ? proposedFileName : originalFileName).getFileName();
                } else if (originalFileName != null) {
                    fileName = Path.of(originalFileName).getFileName();
                }
                file = dir.resolve(fileName);
            }
            storage.copySavegameTo(entry, file);
            return file;
        }

        private String getCurrentOptionalName() {
            var dateName = entry.getDate().toDisplayString(AppPrefs.get().language().getValue().getLocale());
            var name = entry.getName().trim();
            return name.isBlank() || name.equals(dateName) ? null : name;
        }
    }
}
