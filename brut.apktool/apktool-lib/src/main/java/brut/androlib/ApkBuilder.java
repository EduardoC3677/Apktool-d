/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.meta.ApkInfo;
import brut.androlib.meta.SdkInfo;
import brut.androlib.res.AaptInvoker;
import brut.androlib.res.AaptManager;
import brut.androlib.res.data.ResChunkHeader;
import brut.androlib.res.table.ResConfig;
import brut.androlib.res.xml.ResXmlUtils;
import brut.androlib.smali.SmaliBuilder;
import brut.common.BrutException;
import brut.common.Log;
import brut.directory.Directory;
import brut.directory.DirectoryException;
import brut.directory.ExtFile;
import brut.directory.FileDirectory;
import brut.directory.ZipRODirectory;
import brut.util.BackgroundWorker;
import brut.util.BinaryDataInputStream;
import brut.util.BrutIO;
import brut.util.OS;
import brut.util.ZipUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipOutputStream;

public class ApkBuilder {
    private static final String TAG = ApkBuilder.class.getName();

    private final ExtFile mApkDir;
    private final Config mConfig;
    private final AtomicReference<AndrolibException> mFirstError;

    private ApkInfo mApkInfo;
    private SmaliBuilder mSmaliBuilder;
    private AaptInvoker mAaptInvoker;
    private BackgroundWorker mWorker;
    private final Map<String, File> mQuarantinedResources = new LinkedHashMap<>();

    public ApkBuilder(File apkDir, Config config) {
        mApkDir = new ExtFile(apkDir);
        mConfig = config;
        mFirstError = new AtomicReference<>();
    }

    public void build(File outApk) throws AndrolibException {
        if (mConfig.getJobs() > 1) {
            mWorker = new BackgroundWorker(mConfig.getJobs() - 1);
        }
        try {
            mApkInfo = ApkInfo.load(mApkDir);
            String minSdkVersion = mApkInfo.getSdkInfo().getMinSdkVersion();
            mSmaliBuilder = new SmaliBuilder(minSdkVersion != null ? SdkInfo.parseSdkInt(minSdkVersion) : 0);
            mAaptInvoker = new AaptInvoker(mApkInfo, mConfig);

            String apkName = mApkInfo.getApkFileName();
            if (apkName == null) {
                apkName = "out.apk";
            }
            if (mConfig.isNoApk()) {
                outApk = null;
            } else if (outApk == null) {
                outApk = new File(mApkDir, "dist/" + apkName);
            }

            File outDir = new File(mApkDir, "build/apk");
            OS.mkdir(outDir);

            Log.i(TAG, "Using Apktool " + mConfig.getVersion() + " on " + apkName
                     + (mWorker != null ? " with " + mConfig.getJobs() + " threads" : ""));

            buildSources(outDir);
            buildResources(outDir);

            if (mWorker != null) {
                mWorker.waitForFinish();
                if (mFirstError.get() != null) {
                    throw mFirstError.get();
                }
            }

            copyOriginalFiles(outDir);
            if (outApk != null) {
                buildApkFile(outDir, outApk);
            }
        } finally {
            if (mWorker != null) {
                mWorker.shutdownNow();
            }
        }
    }

    private void buildSources(File outDir) throws AndrolibException {
        try {
            Directory in = mApkDir.getDirectory();

            // Copy raw dex files.
            Set<String> dexFiles = new HashSet<>();
            for (String fileName : in.getFiles()) {
                if (fileName.endsWith(".dex")) {
                    copySourcesRaw(outDir, fileName);
                    dexFiles.add(fileName);
                }
            }

            // Build smali dirs.
            for (String dirName : in.getDirs().keySet()) {
                String fileName;
                if (dirName.equals("smali")) {
                    fileName = "classes.dex";
                } else if (dirName.startsWith("smali_")) {
                    fileName = dirName.substring(dirName.indexOf('_') + 1).replace('@', File.separatorChar) + ".dex";
                } else {
                    continue;
                }

                if (!dexFiles.contains(fileName)) {
                    buildSourcesSmali(outDir, dirName, fileName);
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void copySourcesRaw(File outDir, String fileName) throws AndrolibException {
        File inFile = new File(mApkDir, fileName);
        File outFile = new File(outDir, fileName);

        if (!mConfig.isForced() && !isFileNewer(inFile, outFile)) {
            Log.i(TAG, fileName + " has not changed.");
            return;
        }

        Log.i(TAG, "Copying raw " + fileName + "...");
        try {
            BrutIO.copyAndClose(Files.newInputStream(inFile.toPath()), Files.newOutputStream(outFile.toPath()));
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void buildSourcesSmali(File outDir, String dirName, String fileName) throws AndrolibException {
        if (mWorker != null) {
            mWorker.submit(() -> {
                if (mFirstError.get() == null) {
                    try {
                        buildSourcesSmaliJob(outDir, dirName, fileName);
                    } catch (AndrolibException ex) {
                        mFirstError.compareAndSet(null, ex);
                    }
                }
            });
        } else {
            buildSourcesSmaliJob(outDir, dirName, fileName);
        }
    }

    private void buildSourcesSmaliJob(File outDir, String dirName, String fileName) throws AndrolibException {
        File smaliDir = new File(mApkDir, dirName);
        File dexFile = new File(outDir, fileName);

        if (!mConfig.isForced() && !isFileNewer(smaliDir, dexFile)) {
            Log.i(TAG, dirName + " has not changed.");
            return;
        }

        Log.i(TAG, "Smaling " + dirName + " folder into " + fileName + "...");
        mSmaliBuilder.build(smaliDir, dexFile);
    }

    private void buildResources(File outDir) throws AndrolibException {
        File manifest = new File(mApkDir, "AndroidManifest.xml");
        if (!manifest.isFile()) {
            return;
        }

        // Check if manifest is binary XML.
        boolean isBinaryManifest;
        try (BinaryDataInputStream in = new BinaryDataInputStream(Files.newInputStream(manifest.toPath()))) {
            isBinaryManifest = ResChunkHeader.read(in).type == ResChunkHeader.RES_XML_TYPE;
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }

        // Copy raw manifest if it's binary XML.
        if (isBinaryManifest) {
            copyManifestRaw(outDir, manifest);
        }

        // Copy raw resources if possible.
        File arscFile = new File(mApkDir, "resources.arsc");
        if (arscFile.isFile()) {
            copyResourcesRaw(outDir, arscFile);
            return;
        }

        // We cannot build if manifest is binary XML.
        if (isBinaryManifest) {
            return;
        }

        // Build only manifest if no resources.
        File resDir = new File(mApkDir, "res");
        if (!resDir.isDirectory()) {
            buildManifestOnly(outDir, manifest);
            return;
        }

        // Build manifest and resources.
        buildResourcesFully(outDir, manifest, resDir);
    }

    private void copyManifestRaw(File outDir, File manifest) throws AndrolibException {
        if (!mConfig.isForced() && !isFileNewer(manifest, new File(outDir, "AndroidManifest.xml"))) {
            Log.i(TAG, "AndroidManifest.xml has not changed.");
            return;
        }

        Log.i(TAG, "Copying raw AndroidManifest.xml...");
        try {
            Directory in = mApkDir.getDirectory();

            in.copyToDir(outDir, "AndroidManifest.xml");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void copyResourcesRaw(File outDir, File arscFile) throws AndrolibException {
        if (!mConfig.isForced() && !isFileNewer(arscFile, new File(outDir, "resources.arsc"))) {
            Log.i(TAG, "resources.arsc has not changed.");
            return;
        }

        Log.i(TAG, "Copying raw resources.arsc...");
        try {
            Directory in = mApkDir.getDirectory();

            in.copyToDir(outDir, "resources.arsc");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void buildManifestOnly(File outDir, File manifest) throws AndrolibException {
        if (!mConfig.isForced() && !isFileNewer(manifest, new File(outDir, "AndroidManifest.xml"))) {
            Log.i(TAG, "AndroidManifest.xml has not changed.");
            return;
        }

        // Back up manifest for editing.
        File manifestOrig = new File(manifest.getPath() + ".orig");
        try {
            OS.cpfile(manifest, manifestOrig);
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }

        ResXmlUtils.fixingPublicAttrsInProviderAttributes(manifest);

        if (mConfig.isStripCrossPackageMetaData()) {
            String ownPackage = mApkInfo.getResourcesInfo().getPackageName();
            int removed = ResXmlUtils.stripCrossPackageMetaData(manifest, ownPackage);
            if (removed > 0) {
                Log.i(TAG, "Stripped " + removed + " cross-package <meta-data> entry/entries "
                    + "(Dynamic Feature / Play Asset Delivery splits) that aapt2 link cannot resolve.");
            }
        }

        if (mConfig.isDebuggable()) {
            Log.i(TAG, "Setting 'debuggable' attribute to 'true' in AndroidManifest.xml...");
            ResXmlUtils.setApplicationDebugTagTrue(manifest);
        }

        File tmpFile;
        try {
            tmpFile = File.createTempFile("APKTOOL", null);
            OS.rmfile(tmpFile);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }

        Log.i(TAG, "Building AndroidManifest.xml with " + AaptManager.getBinaryName() + "...");
        mAaptInvoker.invoke(tmpFile, manifest, null);

        try (ZipRODirectory tmpDir = new ZipRODirectory(tmpFile)) {
            tmpDir.copyToDir(outDir, "AndroidManifest.xml");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        } finally {
            OS.rmfile(tmpFile);
        }

        // Restore original manifest.
        try {
            OS.mvfile(manifestOrig, manifest);
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void buildResourcesFully(File outDir, File manifest, File resDir) throws AndrolibException {
        if (!mConfig.isForced() && !isFileNewer(manifest, new File(outDir, "AndroidManifest.xml"))
                && !isFileNewer(resDir, new File(outDir, "res"))) {
            Log.i(TAG, "AndroidManifest.xml and resources have not changed.");
            return;
        }

        // Back up manifest for editing.
        File manifestOrig = new File(manifest.getPath() + ".orig");
        try {
            OS.cpfile(manifest, manifestOrig);
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }

        ResXmlUtils.fixingPublicAttrsInProviderAttributes(manifest);

        if (mConfig.isStripCrossPackageMetaData()) {
            String ownPackage = mApkInfo.getResourcesInfo().getPackageName();
            int removed = ResXmlUtils.stripCrossPackageMetaData(manifest, ownPackage);
            if (removed > 0) {
                Log.i(TAG, "Stripped " + removed + " cross-package <meta-data> entry/entries "
                    + "(Dynamic Feature / Play Asset Delivery splits) that aapt2 link cannot resolve.");
            }
        }

        if (mConfig.isDebuggable()) {
            Log.i(TAG, "Setting 'debuggable' attribute to 'true' in AndroidManifest.xml...");
            ResXmlUtils.setApplicationDebugTagTrue(manifest);
        }

        if (mConfig.isNetSecConf()) {
            Log.i(TAG, "Adding permissive network security config in manifest...");
            File netSecConfOrig = new File(mApkDir, "res/xml/network_security_config.xml");
            OS.mkdir(netSecConfOrig.getParentFile());
            ResXmlUtils.modNetworkSecurityConfig(netSecConfOrig);
            ResXmlUtils.setNetworkSecurityConfig(manifest);

            String targetSdkVersion = mApkInfo.getSdkInfo().getTargetSdkVersion();
            if (targetSdkVersion != null && SdkInfo.parseSdkInt(targetSdkVersion) < ResConfig.SDK_NOUGAT) {
                Log.w(TAG, "Target SDK version is lower than 24, Network Security Configuration might be ignored!");
            }
        }

        File tmpFile;
        try {
            tmpFile = File.createTempFile("APKTOOL", null);
            OS.rmfile(tmpFile);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }

        File quarantineDir = null;
        if (mConfig.isResourceQuarantine()) {
            quarantineDir = quarantineUncompilableResources(resDir);
        }

        Log.i(TAG, "Building resources with " + AaptManager.getBinaryName() + "...");
        try {
            mAaptInvoker.invoke(tmpFile, manifest, resDir);
        } finally {
            if (quarantineDir != null) {
                restoreQuarantinedResources(quarantineDir, resDir);
            }
        }

        try (ZipRODirectory tmpDir = new ZipRODirectory(tmpFile)) {
            tmpDir.copyToDir(outDir, "AndroidManifest.xml", "resources.arsc", "res");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        } finally {
            OS.rmfile(tmpFile);
        }

        if (quarantineDir != null) {
            overwriteQuarantinedInOutput(outDir);
        }

        // Restore original manifest.
        try {
            OS.mvfile(manifestOrig, manifest);
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    private File quarantineUncompilableResources(File resDir) throws AndrolibException {
        File quarantineDir;
        try {
            quarantineDir = Files.createTempDirectory("APKTOOL_QUARANTINE").toFile();
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }

        int count = 0;
        File[] typeDirs = resDir.listFiles(File::isDirectory);
        if (typeDirs == null) {
            return quarantineDir;
        }
        for (File typeDir : typeDirs) {
            File[] files = typeDir.listFiles(File::isFile);
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!isUncompilableByAapt2(file)) {
                    continue;
                }
                String relPath = "res/" + typeDir.getName() + "/" + file.getName();
                File staged = new File(quarantineDir, relPath);
                File parent = staged.getParentFile();
                if (parent != null) {
                    OS.mkdir(parent);
                }
                try {
                    OS.mvfile(file, staged);
                    writeStubPng(file);
                } catch (BrutException ex) {
                    throw new AndrolibException(ex);
                }
                mQuarantinedResources.put(relPath, staged);
                count++;
            }
        }
        if (count > 0) {
            Log.i(TAG, "Quarantined " + count + " uncompilable resource file(s) "
                + "(empty/invalid PNGs); they will be packed verbatim into the apk.");
        }
        return quarantineDir;
    }

    private void restoreQuarantinedResources(File quarantineDir, File resDir) throws AndrolibException {
        for (Map.Entry<String, File> entry : mQuarantinedResources.entrySet()) {
            File original = new File(mApkDir, entry.getKey());
            File parent = original.getParentFile();
            if (parent != null) {
                OS.mkdir(parent);
            }
            try {
                OS.cpfile(entry.getValue(), original);
            } catch (BrutException ex) {
                throw new AndrolibException(ex);
            }
        }
    }

    private void overwriteQuarantinedInOutput(File outDir) throws AndrolibException {
        for (Map.Entry<String, File> entry : mQuarantinedResources.entrySet()) {
            String relPath = entry.getKey();
            File compiled = new File(outDir, relPath);
            if (!compiled.isFile()) {
                continue;
            }
            try {
                OS.cpfile(entry.getValue(), compiled);
            } catch (BrutException ex) {
                throw new AndrolibException(ex);
            }
        }
    }

    private static final byte[] STUB_PNG_1x1 = {
        (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
        (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1F, (byte) 0x15, (byte) 0xC4,
        (byte) 0x89, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0B, (byte) 0x49, (byte) 0x44, (byte) 0x41,
        (byte) 0x54, (byte) 0x78, (byte) 0x9C, (byte) 0x63, (byte) 0x60, (byte) 0x00, (byte) 0x02, (byte) 0x00,
        (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x01, (byte) 0x7A, (byte) 0x5E, (byte) 0xAB, (byte) 0x3F,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44,
        (byte) 0xAE, (byte) 0x42, (byte) 0x60, (byte) 0x82,
    };

    private static void writeStubPng(File file) throws AndrolibException {
        try {
            Files.write(file.toPath(), STUB_PNG_1x1);
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    /**
     * A {@code .png} file that does not start with the 8-byte PNG signature
     * (or is zero bytes) cannot be compiled by aapt2 and must be packed into
     * the apk verbatim. Some real-world apps ship such placeholders.
     * 9-patch files (".9.png") are handled the same way - if their PNG
     * signature is missing/garbage, aapt2 cannot patch them either.
     */
    private static boolean isUncompilableByAapt2(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) {
            return false;
        }
        long len = file.length();
        if (len == 0L) {
            return true;
        }
        if (len < 8L) {
            return true;
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] sig = new byte[8];
            int read = 0;
            while (read < 8) {
                int n = in.read(sig, read, 8 - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < 8) {
                return true;
            }
            return !(sig[0] == (byte) 0x89 && sig[1] == 'P' && sig[2] == 'N' && sig[3] == 'G'
                  && sig[4] == 0x0D && sig[5] == 0x0A && sig[6] == 0x1A && sig[7] == 0x0A);
        } catch (IOException ex) {
            return true;
        }
    }

    private void copyOriginalFiles(File outDir) throws AndrolibException {
        if (!mConfig.isCopyOriginal()) {
            return;
        }

        File originalDir = new File(mApkDir, "original");
        if (!originalDir.isDirectory()) {
            return;
        }

        Log.i(TAG, "Copying original files...");
        try {
            FileDirectory in = new FileDirectory(originalDir);

            for (String fileName : in.getFiles(true)) {
                if (ApkInfo.ORIGINAL_FILES_PATTERN.matcher(fileName).matches()) {
                    in.copyToDir(outDir, fileName);
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void buildApkFile(File outDir, File outApk) throws AndrolibException {
        if (outApk.exists()) {
            OS.rmfile(outApk);
        } else {
            File parentDir = outApk.getParentFile();
            if (parentDir != null) {
                OS.mkdir(parentDir);
            }
        }

        // Convert to set for fast lookup.
        Set<String> doNotCompress = new HashSet<>(mApkInfo.getDoNotCompress());

        Log.i(TAG, "Building apk file...");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outApk.toPath()))) {
            // Zip aapt output files.
            ZipUtils.zipDir(outDir, out, doNotCompress);

            // Zip standard raw files.
            for (String dirName : ApkInfo.RAW_DIRS) {
                File rawDir = new File(mApkDir, dirName);
                if (rawDir.isDirectory()) {
                    Log.i(TAG, "Importing " + dirName + "...");
                    ZipUtils.zipDir(mApkDir, dirName, out, doNotCompress);
                }
            }

            // Zip unknown files.
            File unknownDir = new File(mApkDir, "unknown");
            if (unknownDir.isDirectory()) {
                Log.i(TAG, "Importing unknown files...");
                ZipUtils.zipDir(unknownDir, out, doNotCompress);
            }
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
        Log.i(TAG, "Built apk into: " + outApk.getPath());
    }

    private boolean isFileNewer(File file, File reference) {
        return !reference.exists() || BrutIO.recursiveModifiedTime(file) > BrutIO.recursiveModifiedTime(reference);
    }
}
