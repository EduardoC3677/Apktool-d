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
package brut.androlib.res.xml;

import brut.androlib.BaseTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.*;
import static org.junit.Assert.*;

public class StripCrossPackageMetaDataTest extends BaseTest {

    private static final String OWN_PACKAGE = "com.zhiliaoapp.musically";

    private File mManifest;

    @Before
    public void setUp() throws Exception {
        mManifest = File.createTempFile("AndroidManifest", ".xml", sTmpDir);
    }

    @After
    public void tearDown() {
        if (mManifest != null && mManifest.exists()) {
            mManifest.delete();
        }
    }

    @Test
    public void noOpWhenOwnPackageIsNull() throws Exception {
        write(baseManifest(""
            + "  <meta-data android:name=\"x\" "
            + "android:resource=\"@com.other:xml/splits0\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, null);

        assertEquals(0, fixes);
        assertTrue(read().contains("@com.other:xml/splits0"));
    }

    @Test
    public void keepsOwnPackageMetaData() throws Exception {
        write(baseManifest(""
            + "  <meta-data android:name=\"x\" "
            + "android:resource=\"@" + OWN_PACKAGE + ":xml/splits0\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(0, fixes);
        assertTrue(read().contains("@" + OWN_PACKAGE + ":xml/splits0"));
    }

    @Test
    public void stripsCrossPackageResourceMetaData() throws Exception {
        write(baseManifest(""
            + "  <meta-data android:name=\"com.android.vending.splits\" "
            + "android:resource=\"@com.zhiliaoapp.musically.df_live_cast:xml/splits0\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(1, fixes);
        assertFalse(read().contains("df_live_cast"));
    }

    @Test
    public void stripsCrossPackageValueMetaData() throws Exception {
        write(baseManifest(""
            + "  <meta-data android:name=\"com.android.dynamic.apk.fused.modules\" "
            + "android:value=\"@com.zhiliaoapp.musically.df_live_cast:string/modules\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(1, fixes);
        assertFalse(read().contains("df_live_cast"));
    }

    @Test
    public void rewritesCrossPackageIconAttributeToNull() throws Exception {
        write(baseManifest(""
            + "  <activity android:name=\".Main\" "
            + "android:icon=\"@com.zhiliaoapp.musically.df_live_cast:drawable/ic_main\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(1, fixes);
        String result = read();
        assertFalse(result.contains("df_live_cast"));
        assertTrue(result.contains("android:icon=\"@null\""));
    }

    @Test
    public void leavesAndroidNamespaceReferencesAlone() throws Exception {
        write(baseManifest(""
            + "  <activity android:name=\".Main\" "
            + "android:theme=\"@android:style/Theme.NoTitleBar\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(0, fixes);
        assertTrue(read().contains("@android:style/Theme.NoTitleBar"));
    }

    @Test
    public void handlesPlusPrefixedPackageId() throws Exception {
        write(baseManifest(""
            + "  <activity android:name=\".Main\" "
            + "android:label=\"@+com.other.split:string/title\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(1, fixes);
        assertTrue(read().contains("android:label=\"@null\""));
    }

    @Test
    public void mixedFixesAreCountedTogether() throws Exception {
        write(baseManifest(""
            + "  <meta-data android:name=\"a\" "
            + "android:resource=\"@com.other.split:xml/splits0\"/>\n"
            + "  <activity android:name=\".A\" "
            + "android:icon=\"@com.other.split:drawable/i\"/>\n"
            + "  <activity android:name=\".B\" "
            + "android:label=\"@com.other.split:string/l\"/>\n"
            + "  <meta-data android:name=\"keep\" "
            + "android:value=\"plain string\"/>"));

        int fixes = ResXmlUtils.stripCrossPackageMetaData(mManifest, OWN_PACKAGE);

        assertEquals(3, fixes);
        String result = read();
        assertFalse(result.contains("com.other.split"));
        assertTrue(result.contains("plain string"));
    }

    private static String baseManifest(String applicationBody) {
        return ""
            + "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
            + "package=\"" + OWN_PACKAGE + "\">\n"
            + "  <application>\n"
            + applicationBody + "\n"
            + "  </application>\n"
            + "</manifest>\n";
    }

    private void write(String content) throws Exception {
        Files.write(mManifest.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read() throws Exception {
        return new String(Files.readAllBytes(mManifest.toPath()), StandardCharsets.UTF_8);
    }
}
