package org.apache.commons.lang;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

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
    public void testBugReproduction(@From(LocaleGen.class) Locale locale) {
        boolean validIf = true;
        // isAvailableLocale is called without availableLocaleSet() ever being called
        boolean buggyIf = true;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

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
}
