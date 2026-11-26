package org.apache.commons.lang;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@RunWith(JQF.class)
public class JQF_LocaleUtilsTest {

    public static class LocaleGen extends Generator<Locale> {
        public LocaleGen() { super(Locale.class); }

        @Override
        public Locale generate(SourceOfRandomness r, GenerationStatus status) {
            int kind = r.nextInt(10);
            if (kind == 0) {
                return null;
            }
            if (kind <= 4) {
                Locale[] avail = Locale.getAvailableLocales();
                return avail[r.nextInt(avail.length)];
            }
            if (kind == 5) {
                return Locale.US;
            }
            StringBuilder lang = new StringBuilder();
            int ll = 2 + r.nextInt(2);
            for (int i = 0; i < ll; i++) {
                lang.append((char) ('a' + r.nextInt(26)));
            }
            String country = "";
            if (r.nextBoolean()) {
                StringBuilder c = new StringBuilder();
                for (int i = 0; i < 2; i++) {
                    c.append((char) ('A' + r.nextInt(26)));
                }
                country = c.toString();
            }
            return new Locale(lang.toString(), country);
        }
    }

    @Fuzz
    public void testBugReproduction(@From(LocaleGen.class) Locale locale,
                                    boolean availableSetAlreadyInitialized) {
        boolean validIf = true;
        // bug: isAvailableLocale is called while the cached set has not been initialized
        boolean buggyIf = !availableSetAlreadyInitialized;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        // Reset the lazily-initialized cache to emulate a fresh JVM state
        try {
            Field f = LocaleUtils.class.getDeclaredField("cAvailableLocaleSet");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) {
            // field absent: nothing to reset
        }
        if (availableSetAlreadyInitialized) {
            LocaleUtils.availableLocaleSet();
        }

        boolean result = false;
        String exception = "none";
        try {
            result = LocaleUtils.isAvailableLocale(locale);
        } catch (Throwable t) {
            exception = t.getClass().getName();
        }

        final boolean fResult = result;
        final String fException = exception;
        Log.logOutIf(true, () -> new String[]{ String.valueOf(fResult), fException });

        Set expectedSet = new HashSet(Arrays.asList(Locale.getAvailableLocales()));
        boolean expected = locale != null && expectedSet.contains(locale);
        assertEquals("none", fException);
        assertEquals(expected, fResult);
    }

    @Fuzz
    public void testIsAvailableLocale(@From(LocaleGen.class) Locale locale,
                                    boolean availableSetAlreadyInitialized) {
        boolean validIf = true;
        boolean buggyIf = !availableSetAlreadyInitialized;
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            // Reset the lazily-initialized cache to emulate a fresh JVM state
            try {
                Field f = LocaleUtils.class.getDeclaredField("cAvailableLocaleSet");
                f.setAccessible(true);
                f.set(null, null);
            } catch (Exception e) {
                // field absent: nothing to reset
            }
            if (availableSetAlreadyInitialized) {
                LocaleUtils.availableLocaleSet();
            }

            boolean result = false;
            String exception = "none";
            try {
                result = LocaleUtils.isAvailableLocale(locale);
            } catch (Throwable t) {
                exception = t.getClass().getName();
            }

            final boolean fResult = result;
            final String fException = exception;
            final String fLocale = String.valueOf(locale);
            final boolean fInit = availableSetAlreadyInitialized;
            Log.logOutIf(preserveIf, () -> new String[]{ String.valueOf(fResult), fException, fLocale, String.valueOf(fInit) });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
