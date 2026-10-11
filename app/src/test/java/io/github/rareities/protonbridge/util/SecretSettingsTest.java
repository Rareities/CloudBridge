package io.github.rareities.protonbridge.util;

import android.app.Application;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import java.io.IOException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SecretSettingsTest {
    private SecretKey key() throws Exception { KeyGenerator generator=KeyGenerator.getInstance("AES"); generator.init(256); return generator.generateKey(); }
    @Test public void legacyPasswordMigratesWithoutPlaintextOrCrossSettingReuse() throws Exception {
        Application app=ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(app).edit().putString("proxy", "canary-proxy-secret").commit();
        SecretKey key=key();
        SecretSettings store=new SecretSettings(app, create->key);
        assertEquals("canary-proxy-secret", store.read("proxy"));
        assertFalse(PreferenceManager.getDefaultSharedPreferences(app).contains("proxy"));
        String record=app.getSharedPreferences(SecretSettings.PREFS,0).getString("proxy",null);
        assertFalse(record.contains("canary-proxy-secret"));
        app.getSharedPreferences(SecretSettings.PREFS,0).edit().putString("other",record).commit();
        assertThrows(IOException.class,()->store.read("other"));
        assertEquals("canary-proxy-secret",store.read("proxy"));
    }
    @Test public void missingOrWrongKeyFailsClosedAndRetainsRecoverableCiphertext() throws Exception {
        Application app=ApplicationProvider.getApplicationContext(); SecretKey key=key(), other=key();
        SecretSettings store=new SecretSettings(app, create->key);
        store.write("proxy","p");
        String before=app.getSharedPreferences(SecretSettings.PREFS,0).getString("proxy",null);
        assertThrows(IOException.class,()->new SecretSettings(app,create->other).read("proxy"));
        assertEquals(before,app.getSharedPreferences(SecretSettings.PREFS,0).getString("proxy",null));
        store.write("proxy",""); assertEquals("",store.read("proxy"));
    }
}
