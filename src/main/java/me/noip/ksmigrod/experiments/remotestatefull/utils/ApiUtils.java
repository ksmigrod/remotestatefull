/*
 * The MIT License
 *
 * Copyright 2018 Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.noip.ksmigrod.experiments.remotestatefull.utils;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.internet.ContentDisposition;

/**
 *
 * @author Krzysztof Śmigrodzki <Krzysztof.Smigrodzki@mf.gov.pl>
 */
public class ApiUtils {

    private static final Logger log = Logger.getLogger(ApiUtils.class.getName());

    private static final Pattern ENCODED_VALUE_PATTERN = Pattern.compile("%[0-9a-f]{2}|\\S", Pattern.CASE_INSENSITIVE);

    /**
     * Koduje nazwę pliku w RFC5987 dla pola filename*
     *
     * @param s Nazwa pliku do zakodowania.
     * @return Zakodowana nazwa pliku.
     */
    public static String encodeRFC5987(final String s) {
        final byte[] rawBytes = s.getBytes(StandardCharsets.UTF_8);
        final int len = rawBytes.length;
        final StringBuilder sb = new StringBuilder(len << 1);
        sb.append("UTF-8''");
        final char[] digits = {'0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
        final byte[] attributeChars = {'!', '#', '$', '&', '+', '-', '.', '0', '1', '2', '3', '4',
            '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
            'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '^', '_', '`',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
            'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '|', '~'};
        for (final byte b : rawBytes) {
            if (Arrays.binarySearch(attributeChars, b) >= 0) {
                sb.append((char) b);
            } else {
                sb.append('%');
                sb.append(digits[15 & (b >>> 4)]);
                sb.append(digits[b & 15]);
            }
        }
        return sb.toString();
    }

    public static String getFileNameFromContentDisposition(ContentDisposition contentDisposition) {
        String filename = null;
        if (contentDisposition != null) {
            filename = contentDisposition.getParameter("filename");
            String filenameU = contentDisposition.getParameter("filename*");
            if (filenameU != null) {
                try {
                    filename = ApiUtils.decodeRFC5987(filenameU);
                } catch (UnsupportedEncodingException ex) {
                    log.log(Level.WARNING, "Dekodowanie filename*", ex);
                }
            }
        }
        return filename;
    }

    /**
     * Koduje nazwę pliku dla pola filename.
     *
     * Usuwa znaki diakrytyczne, zamienia znaki spoza ASCII na podkreślenia.
     *
     * @param s Nazwa pliku do zakodowania.
     * @return Zakodowana nazwa pliku.
     */
    public static String toAscii(final String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-zA-Z0-9._\\-+,@$!~'=()\\[\\]{}]", "_");
    }

    private static String decode(String s, String encoding) throws UnsupportedEncodingException {
        Matcher matcher = ENCODED_VALUE_PATTERN.matcher(s);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (matcher.find()) {
            String matched = matcher.group();
            if (matched.startsWith("%")) {
                Integer value = Integer.parseInt(matched.substring(1), 16);
                bos.write(value);
            } else {
                bos.write(matched.charAt(0));
            }
        }
        return new String(bos.toByteArray(), encoding);
    }

    /**
     * Odkodowuje nazwę pliku z RFC5987.
     *
     * @param s Zakodowana nazwa pliku poprzedzona kodowaniem.
     * @return Nazwa pliku po odkodowaniu.
     * @throws UnsupportedEncodingException
     */
    public static String decodeRFC5987(String s) throws UnsupportedEncodingException {
        int first = s.indexOf('\'');
        if (first == -1 || first == s.length() - 1) {
            throw new UnsupportedEncodingException("Niepoprawny RFC5987 pierwszy apostrof");
        }
        int second = s.indexOf('\'', first + 1);
        if (second == -1 || second == s.length() - 1) {
            throw new UnsupportedEncodingException("Niepoprawny RFC5987 drugi apostrof");
        }
        String encodingName = s.substring(0, first);
        return decode(s.substring(second + 1), encodingName);
    }

}
