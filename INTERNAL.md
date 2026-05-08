# Releasing a new version.

The steps taken for slicing an official release of Apktool.

### Refreshing prebuilt aapt2 binaries

The `aapt2` binaries shipped under
`brut.apktool/apktool-lib/src/main/resources/prebuilt/{linux,macosx,windows}/`
are vendored copies pulled from the Android SDK build-tools package. The
current vendored revision is build-tools **36.0.0**
(`Android Asset Packaging Tool (aapt) 2.20-13193326`). To bump them to a
newer build-tools release run:

```bash
# Default: bootstrap cmdline-tools, install build-tools via sdkmanager,
# copy the host-native aapt2 into the matching prebuilt/ folder.
scripts/refresh-aapt2.sh

# Pin a specific build-tools version (the script defaults to 36.0.0):
BUILD_TOOLS_VERSION=36.0.0 scripts/refresh-aapt2.sh

# Refresh all three platforms from a single host by downloading the
# per-OS build-tools archives from dl.google.com directly:
MIRROR_FROM_REMOTE=1 BUILD_TOOLS_VERSION=36.0.0 scripts/refresh-aapt2.sh
```

The `dl.google.com` archive layout changed at r36: the platform-suffix
separator went from `-` (`build-tools_r35-linux.zip`) to `_`
(`build-tools_r36_linux.zip`). The `remote_archive_token` helper inside the
script picks the correct form per major revision; if Google ever changes the
naming scheme again, that helper is the only place to update.

After running, verify the new binaries with `aapt2 version` and run the full
test suite (`./gradlew build`) before committing.

### Refreshing the bundled Android framework jar

`brut.apktool/apktool-lib/src/main/resources/prebuilt/android-framework.jar`
is the framework that apktool implicitly links against during `b`/`build` when
no explicit `-p` is provided. It is **not** a full `android.jar` - apktool
only needs the resource table and manifest. The current bundled framework is
**Android 16 (API 36, platform-36_r02)**.

To refresh it from a newer platform release:

1. Download the platform package, e.g.
   `curl -fSL -O https://dl.google.com/android/repository/platform-36_r02.zip`.
2. Unzip and locate `android-<api>/android.jar`.
3. Extract just `AndroidManifest.xml` and `resources.arsc` from `android.jar`.
4. Repack them - with deterministic timestamps - into a fresh
   `android-framework.jar`:

   ```bash
   unzip -j android.jar AndroidManifest.xml resources.arsc -d fwk/
   (cd fwk && find . -exec touch -t 200801010000.00 {} + && \
       zip -X -q ../android-framework.jar AndroidManifest.xml resources.arsc)
   ```

5. Move `android-framework.jar` into the `prebuilt/` folder and run the
   gradle test suite.

### Exposing new aapt2 features

When a new aapt2 release adds CLI options worth surfacing to apktool users,
the flags need to be plumbed in three places:

1. `brut.apktool/apktool-lib/src/main/java/brut/androlib/Config.java` -
   add a backing field, default, getter and setter.
2. `brut.apktool/apktool-lib/src/main/java/brut/androlib/res/AaptInvoker.java` -
   forward the value to the `aapt2 compile` or `aapt2 link` command builder.
3. `brut.apktool/apktool-cli/src/main/java/brut/apktool/Main.java` - declare
   the `Option`, register it in `loadOptions` (advanced mode unless trivial)
   and parse it in `cmdBuild` / `cmdDecode`.

Currently exposed advanced build flags backed by aapt2 features include
`--png-compression-level`, `--no-resource-removal` and
`--proguard-conditional-keep-rules`. `--enable-sparse-encoding`,
`--enable-compact-entries`, `--feature-flags`, `--no-compile-sdk-metadata`,
`--rename-resources-package` and `--warn-manifest-validation` are also wired
but are driven from `ApkInfo` / `ResourcesInfo` (not from a top-level CLI
flag) so they round-trip without manual user input.

### aapt2 36 stricter validation - fixture fallout

aapt2 36 stable hardened several validations that previous releases either
skipped or covered only with `--legacy`. Two patterns observed against the
in-repo `testapp` fixture were addressed at the fixture level:

1. `<item type="layout" ... format="string">TEXT</item>` (used in
   `res/values-mcc001/layouts.xml` for the issue #4096 round-trip case) is
   now rejected with `error: invalid value for type 'layout'. Expected a
   reference.` - even when `--legacy` is passed. Fixtures must be
   reference-typed if the resource type is `layout`.
2. Resources defined under both `xxhdpi` and the explicit `xxhdpi-v4` (or
   any qualifier whose `-v<N>` floor is implicit) collide at link time with
   `error: resource '<name>' has a conflicting value for configuration
   (xxhdpi-v4)`. aapt2 36 normalises these to a single key, so the
   duplicate folder was removed.

### Natural SDK-version floor stripping

aapt2 36 stamps an implicit `-v<N>` floor onto every binary resource entry
whose qualifier set requires a minimum SDK level (e.g. any density implies
v4, `anydpi` implies v21, `round`/`notround` imply v23, color-mode and
HDR imply v26, grammatical gender implies v34, etc.). Earlier aapt2
releases left those floors off the binary entry, so apktool's decoder
emitted folders like `drawable-nodpi` directly. With aapt2 36 the binary
table reports `sdkVersion = 4` for that entry and the decoder therefore
produced `drawable-nodpi-v4`, breaking round-trips and 16 tests in
`BuildAndDecodeApkTest`.

`ResConfig.naturalSdkVersion()` mirrors AOSP's
`applyVersionForCompatibility` algorithm and returns the implicit floor for
the current qualifier combination. `computeQualifiers` only emits the
`-v<N>` suffix when `mSdkVersion > naturalSdkVersion()`, so the implicit
floor is stripped on decode and the canonical folder name (`drawable-nodpi`,
`values-round`, `values-feminine`, `values-watch`, ...) is restored. The
round-trip is now idempotent across `decode -> build -> decode`.

Floor table currently implemented (matches the AOSP source as of
aapt2 36 / build-tools 36.0.0):

| Qualifier feature                                         | Min SDK |
| --------------------------------------------------------- | ------- |
| Grammatical gender (`feminine`, `neuter`, `masculine`)    | 34      |
| Color mode (`widecg`, `nowidecg`, `highdr`, `lowdr`)      | 26      |
| Screen-round (`round`, `notround`)                        | 23      |
| Density `anydpi`                                          | 21      |
| Smallest/screen width/height in dp (`sw100dp`, `w200dp`)  | 13      |
| Any `uiMode` type or night mode                           | 8       |
| Any non-default density, screen-size or screen-long       | 4       |

### Ensuring proper license headers

_Currently broken after movement to kotlin dsl._

### Tagging the release.

Inside `build.gradle` there are two lines.

    version
    suffix

The version variable should be left unchanged. If done correctly, it will already be the version
you are about to release. In this case `2.2.2`. The suffix variable should read `SNAPSHOT` as
the `2.2.2` release up until this point was `SNAPSHOT` releases (Unofficial).

We need to remove the `SNAPSHOT` portion and leave the minor version blank. An example can be
found [here](https://github.com/iBotPeaches/Apktool/commit/96b70d0be7513c5a1e5d3a3b9a75e4e2b076ad79).

After we remove `SNAPSHOT` we need to make the version commit. Organization and following patterns
is crucial here. This commit should have 1 change only - the change above. Now commit this change
with the commit message - `version bump (x.x.x)`.

At this point we now have the commit of the release, but we need to tag it using the following message.

    git tag -a vx.x.x -m "changed version to vx.x.x" -s

For example for the `2.2.1` release.

    git tag -a v2.2.1 -m "changed version to v2.2.1" -s

### Prepare for publishing.

New to Apktool is publishing releases to Maven, so plugin authors can directly integrate. You
need a `gradle.properties` file in root with the structure:

```
signing.keyId={gpgKeyId}
signing.password={gpgPassphrase}
signing.secretKeyRingFile={gpgSecretKingRingLocation}

ossrhUsername={sonatypeUsername}
ossrhPassword={sonatypePassword}
```

Release with maven with `./gradlew build shadowJar release publish`.

### Building the binary.

> [!IMPORTANT]
> In order to publish Maven artifacts you need JDK 11+.

In order to maintain a clean slate. Run `gradlew clean` to start from a clean slate. Now lets build
the new binary version. We should not have any new commits since the tagged commit.

    ./gradlew build shadowJar proguard release

The build should tell you what version you are building, and it should match the commits you made previously.

    ➜ Apktool git:(main) ./gradlew build shadowJar proguard release
    Building RELEASE (main): 2.2.2

### Testing the binary.

Now the release binary is built in the same location as all other builds. Run this version against
some of the fixed bugs in this release. This is a simple test to ensure the build had no errors.

Copy the jar to any location to prep for uploading. The pattern we name the jars is

    apktool_x.x.x.jar

Or in the case of the last release - `apktool_2.2.1.jar`

Once you have the jar in this form. Record the md5 hash & sha256 hash of it. This can be done using `md5sum`
and `sha256sum` on unix systems.

This can be shown for the `2.2.2` release like so

    ➜  Desktop md5sum apktool_2.2.2.jar
    1e6be08d3f9bb4b442bb85cf4e21f1c1  apktool_2.2.2.jar

    ➜  Desktop sha256sum apktool-2.2.2.jar
    1f1f186edcc09b8677bc1037f3f812dff89077187b24c8558ca2a89186ea3251  apktool-2.2.2.jar

Remember these hashes. These are the local hashes. These are our main hashes. All others (Bitbucket, Backup)
must match these. If they do not - they are invalid.

### Lets get uploading.

Lets make sure we actually pushed these release changes to the repo (Both Github & Bitbucket)

    git push origin main
    git push origin vx.x.x

    git push bitbucket master
    git push bitbucket vx.x.x

We upload the binaries into 3 places.

1. [Bitbucket Downloads](https://bitbucket.org/iBotPeaches/apktool/downloads)
2. [Github Releases](https://github.com/iBotPeaches/Apktool/releases) - Since `2.2.1`.
3. [Backup Mirror](https://connortumbleson.com/apktool/)
4. [Sonatype (Maven)](https://oss.sonatype.org)

#### Bitbucket

This one is pretty easy. Head to the URL attached to the hyperlink #1 above. There will be a "Add Files"
button on the top right of the page. Upload the `apktool_x.x.x.jar` file.

After it is uploaded. Immediately visit the page and download it. Check the `md5` for a match.

#### GitHub

This option will not work until the tag is pushed. You can head to this [page](https://github.com/iBotPeaches/Apktool/releases/new)
to draft a new release. The `Tag version` dropdown will have the new tag. In this case `v2.2.2`.

Select that option and make the title `Apktool vx.x.x`. There will be a description field on this release.
Hold tight, we link the release blog post in this field, but we can edit the release after the fact to add this.

Upload the binary `apktool_x.x.x.jar` and submit the release.

#### Backup Server

Access to this server is probably limited so this option may not be possible. SSH into the
`connortumbleson.com` server with username `connor`. Head to `public_html/apktool` and upload
the `apktool_x.x.x.jar` to it.

Now re-generate the md5/sha256 hashes for these files.

    md5sum *.jar > md5.md5sum
    sha256 *.jar > sha256.shasum

Check the `md5.md5sum` file for the hashes. The file will look something like this.

    6de3e097943c553da5db2e604bced332  apktool_1.4.10.jar
    ...
    1e6be08d3f9bb4b442bb85cf4e21f1c1  apktool_2.2.2.jar

Additionally check the `sha256.shasum` file for the hashes. This file will look almost identical to the above
except for containing sha256 hashes.

The hashes match so we are good with the backup server.

#### Sonatype

You'll want to log in and view the Staging repositories and confirm you see the recently made build. You'll want to:

 * Close it (Wait for audit report email)
 * Release it (Drop the staging repository)
 * Wait 20min - 2 hours for it to appear [here](https://mvnrepository.com/artifact/org.apktool/apktool-lib)

With those done, time to get writing the release post.

We currently blog the releases on the [Connor Tumbleson personal blog](https://connortumbleson.com/).
This may change and the formatting of these release posts change over time.

Some recent releases for understanding the pattern can be found below.

1. [2.2.1](https://connortumbleson.com/2016/10/18/apktool-v2-2-1-released/)
2. [2.2.0](https://connortumbleson.com/2016/08/07/apktool-v2-2-0-released/)
3. [2.0.2](https://connortumbleson.com/2015/10/12/apktool-v2-0-2-released/)
4. [2.0.0](https://connortumbleson.com/2015/04/20/apktool-v2-0-0-released/)

For obtaining commit authors and counts. The following command does the legwork:

    git shortlog -s -n --all --no-merges --since="05 Sept 2018"

Obviously replacing the date with the release date of the last version.

So write the post. I tend to always include the following:

1. Image of release for featured image when reshared on socials.
2. Quick sentence or two for SEO to describe the meat of this release.
3. Commit count and total for this release with author names.
4. Changelog linking to the bugs that were fixed.
5. Download including the md5/sha256 hash.
6. Link dump to Project Site, GitHub, Bug Tracker and XDA Thread.

Now that you've written this post. We need to go post it in places and update places where
Apktool is released.

### XDA Thread

We have a [thread](https://forum.xda-developers.com/showthread.php?t=1755243) on XDA Developers.
This thread follows the same pattern for all releases.

When writing a response to the XDA thread we follow another pattern of release notes. These examples
can be found below:

1. [2.2.2](https://forum.xda-developers.com/showpost.php?p=70687935&postcount=4635)
2. [2.2.1](http://forum.xda-developers.com/showpost.php?p=69188139&postcount=4478)
3. [2.0.0](http://forum.xda-developers.com/showpost.php?p=60255972&postcount=3063)

### Apktool Website

The Apktool project website has a few locations to update:

1. The homepage intro
2. The download link in header
3. Migrating `unreleased.mx` to a new blog post.

The easiest way to describe this is to just link to a [previous release](https://github.com/iBotPeaches/Apktool/pull/3146/files).

### Update Milestones

Now that we've released a version, we should hopefully have no more tickets in the release just published.
If there are, move those tickets to the next milestone.

You can head to [milestones](https://github.com/iBotPeaches/Apktool/milestones) to close the just
released version and create another.

I tend to create the next release (In this case `2.2.3`) with an ETA of 3 months in the future. This
is just a guideline but helps me to release a new version every 3 months.

### Social Spam

The final step is to send this release into the wild via some social posting. Head to the blog
where the release post was and send that link to Twitter, Google and whatever else you use.

Relax and watch the bug tracker.

# Building aapt2 binaries.

The steps taken for building our modified aapt2 binaries for apktool.

### Getting the modified `frameworks/base` repo.
First step is using the [platform_frameworks_base](https://github.com/iBotPeaches/platform_frameworks_base) repo.

While previously unorganized, the repo now follows the branch naming convention depending on the current Android version.
So `apktool_7.1` corresponds to the 7.1 Android release. This branch should work for all `android-7.1.x` tags for AOSP.

We didn't follow this naming convention until Android 7.1. So don't go looking for older versions. The current version
is `apktool-9.0.0`, which corresponds to the Android 9.0 (Pie) release.

This repo has a variety of changes applied. These changes range from disabling optimizations to lessening the rules
that aapt regularly has. We do this because apktool's job is to not fix apks, but rather keep them as close to the
original as they were.

### First we need the AOSP source

As cheesy as it is, just follow this [downloading](https://source.android.com/source/downloading.html) link in order
to get the source downloaded. This is no small download, expect to use 150-250GB.

Some optimization techniques for a smaller clone:

 * `~/bin/repo init -u https://android.googlesource.com/platform/manifest -b android16-release --partial-clone` - Partial clone
 * `repo sync -c` - Only current branch

After that, you need to build AOSP via this [documentation](https://source.android.com/source/building.html) guide. Now
we aren't building the entire AOSP package, the initial build is to just see if you are capable of building it.

We check out a certain tag or branch. Currently, we use

 * aapt2 - `android-16-release`

### Including our modified `frameworks/base` package.

There is probably a more automated way to do this, but for now:

1. `cd frameworks/base`
2. `git remote add origin git@github.com:iBotPeaches/platform_frameworks_base.git`
3. `git fetch origin -v`
4. `git checkout origin/apktool-{x}`

#### Mac Patch

Normally you'll be building this on a recent macOS that isn't supported. You'll want to follow these steps:

1. `vim build/soong/cc/config/darwin_host.go`
2. Find `darwinSupportedSdkVersions` array.
3. Add number that corresponds to output of: `find /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs -iname "*.sdk"`

### Building the aapt2 binary.

The steps below are different per flavor and operating system.

#### Linux / Windows
1. `source build/envsetup.sh`
1. `lunch aosp_cf_x86_64_only_phone-aosp_current-eng`
1. `m aapt2`
1. `strip out/host/linux-x86/bin/aapt2`
1. `strip out/host/linux-x86/bin/aapt2_64`
1. `strip out/host/windows-x86/bin/aapt2.exe`
1. `strip out/host/windows-x86/bin/aapt2_64.exe`

#### Mac
1. `export ANDROID_JAVA_HOME=/Path/To/Jdk`
1. `source build/envsetup.sh`
1. `lunch aosp_cf_x86_64_only_phone-aosp_current-eng`
1. `m aapt2`
1. `strip out/host/darwin-x86/bin/aapt2_64`

#### Confirming aapt/aapt2 builds are static

There are some issues with some dependencies (namely `libc++`) in which they are built in the shared state. This is
alright in the scope and context of AOSP/Android Studio, but once you leave those two behind and start using aapt on
its own, you encounter some issues. The key is to force `libc++` to be built statically which takes some tweaks with the
AOSP build systems as that dependency isn't standard like `libz` and others.

You can test the finalized project using tools like `ldd` (unix) and `otool -L` (mac) for testing the binaries looking
for shared dependencies.

# Gradle Tips n Tricks

    ./gradlew build shadowJar proguard -x test

This skips the testing suite (which currently takes 2-4 minutes). Use this when making quick builds and save the testing
suite before pushing to GitHub.

    ./gradlew test --debug-jvm

This enables debugging on the test suite. This starts the debugger on port 5005 which you can connect with IntelliJ.

    ./gradlew :brut.apktool:apktool-lib:test ---tests "*BuildAndDecodeTest"

This runs the library project of Apktool, selecting a specific test to run. Comes in handy when writing a new test and
only wanting to run that one. The asterisk is used to the full path to the test can be ignored. You can additionally
match this with the debugging parameter to debug a specific test. This command can be found below.

    ./gradlew :brut.apktool:apktool-lib:test --tests "*BuildAndDecodeTest" --debug-jvm

# Toolchain & dependency policy

## Java compatibility

The minimum supported Java version is **11**. The CI matrix in
`.github/workflows/build.yml` exercises **JDK 11, 17, and 21** across
ubuntu/macOS/windows. Release artifacts in `build-release.yml` are built with
**JDK 21**.

The bytecode target is enforced through `options.release.set(11)` on every
`JavaCompile` task in `build.gradle.kts`, which guarantees both source-level
and bytecode-level Java 11 compatibility regardless of which JDK Gradle is
running under (so a developer on JDK 21 cannot accidentally ship Java 21
bytecode).

JDK 8 is no longer supported; `javac --release 8` was removed when JDK 21
flagged it as obsolete.

## smali / baksmali source

`smali` and `smali-baksmali` are pulled directly from **Google Maven**
(`google()` repository, group `com.android.tools.smali`). They are **not**
mirrored to Maven Central - any attempt to fetch them from `mavenCentral()`
will 404. The previous JitPack source for `com.github.iBotPeaches.smali` is
no longer used; do not re-add the JitPack repository unless Google stops
publishing.

To bump smali, check the canonical metadata file:
`https://dl.google.com/android/maven2/com/android/tools/smali/smali/maven-metadata.xml`
and update both `baksmali` and `smali` versions in
`gradle/libs.versions.toml` (they are released in lockstep).

## Other dependency bumps

When bumping versions in `gradle/libs.versions.toml`, prefer canonical
`maven-metadata.xml` over Maven Central's stale `solrsearch` results, which
have been observed to lag releases for `commons-io` and `guava` by multiple
versions.

# Hardened build path for obfuscated apks

Apps that aggressively defend against re-engineering (TikTok being the
canonical example) ship payloads that break the default `apktool b` path
in three independent ways. As of the changes made on this branch, all
three are auto-detected and worked around. None of the workarounds
compromise normal apks.

## 1. Empty / non-PNG `.png` resources

Some apps include thousands of zero-byte placeholder files with the
`.png` extension. When the file is shipped in the original apk it is
just a stored zip entry with size 0; aapt2 was never asked to compile
it. On rebuild, `apktool` writes the file back into `res/<type>/<name>.png`
and `aapt2 compile --dir <res>` then tries to crunch it, fails with
"file does not start with PNG signature", and aborts the entire build.

`ApkBuilder.quarantineUncompilableResources` runs before `aapt2 compile`,
moves every `.png` whose first 8 bytes are not the PNG signature
(`89 50 4E 47 0D 0A 1A 0A`) to a temporary directory, leaves a
deterministic 1×1 transparent stub PNG in its place so aapt2 produces
a valid `.flat` and the resource id stays defined, then post-link
overwrites the compiled stub in the staging directory with the original
zero-byte payload before the final zip step. The result is an apk that
is byte-identical to the original for those entries.

CLI flag: `--no-resource-quarantine` restores strict aapt2 behavior.
Config knob: `Config#setResourceQuarantine(false)`.
Default: enabled.

## 2. `<meta-data android:resource="@<other-pkg>:type/name">`

Apps that ship Dynamic Feature / Play Asset Delivery splits embed
`<meta-data>` entries in the base apk's manifest that point at
resources in a *different package* (e.g.
`@com.zhiliaoapp.musically.df_live_cast:xml/splits0`). Those references
resolve at install time through `PackageManager`; aapt2 link, however,
treats them as link-time references and aborts with
"resource not found".

`ResXmlUtils.stripCrossPackageMetaData` removes such entries from the
manifest before the rebuild and the original `.orig` file is restored
afterwards. The runtime PackageManager lookup is unaffected because the
splits ride on a separate apk anyway.

CLI flag: `--keep-cross-package-metadata` keeps them (use only when you
have already linked the referenced split packages in the same build).
Config knob: `Config#setStripCrossPackageMetaData(false)`.
Default: enabled.

## 3. App-package attributes mislabeled as `android:` in binary XML

The most subtle defense: a layout's `XML_RES_ATTRIBUTE` chunk has
`attr.name` pointing at a resource in the app package (`0x7f060e82` =
`@attr/c1k`), which resolves correctly to `res-auto`, but the chunk's
`attr.ns` deliberately points at the string-pool entry for
`http://schemas.android.com/apk/res/android`. apktool would then write
the attribute as `android:c1k="..."`, and aapt2 link would abort with
"attribute android:c1k not found" because no such system attribute
exists.

Fix in `BinaryXmlResourceParser.getAttributeNamespace`: when the
attribute's resource id is in the app package (`pkgId == 0x7f`) **and**
the declared URI is `http://schemas.android.com/apk/res/android`, the
resource id wins. The attribute is routed to res-auto via
`getNonDefaultNamespaceUri`. Well-formed apks (where attr.ns honestly
points at android) are unaffected because their attr.name resolves to
a system resource id (`pkgId == 0x01`).

`getNonDefaultNamespaceUri` itself was reworked to scan the live
namespace stack for a non-android URI instead of indexing it with an
attribute index (a long-standing latent bug).

There is no opt-out flag; the heuristic is deterministic and only fires
on the precise `(app-package id) ⊕ (android namespace)` mismatch.

## 4. `apktool b` UX guard

`apktool b` with no arguments used to silently default to the current
directory and crash with `NoSuchFileException: ./apktool.yml` when run
from anywhere except a decoded apk root. It now refuses up front with a
short error and prints usage. `apktool b <dir>` is unchanged.

