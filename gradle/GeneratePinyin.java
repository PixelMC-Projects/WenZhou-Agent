import com.ibm.icu.text.Transliterator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Extracts the ICU 71.1 Han-Latin search readings. Runs only during the build. */
class GeneratePinyin {
    private static final Pattern CONTEXT_RULE =
            Pattern.compile("^\\{\\\\[uU]([0-9A-F]+)\\}' '\\?\\\\[uU]([0-9A-F]+) > .*;$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s'’]+");

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var han = Transliterator.getInstance("Han-Latin");
        var normal = Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower");
        try (var writer = Files.newBufferedWriter(output.resolve("han-latin.tsv"), StandardCharsets.UTF_8)) {
            int count = 0;
            for (var range : han.getSourceSet().ranges()) {
                for (int point = range.codepoint; point <= range.codepointEnd; point++) {
                    String character = Character.toString(point);
                    String reading = reading(normal, character);
                    if (!reading.equals(character) && !han.transliterate(character).equals(character)) {
                        writer.write(Integer.toHexString(point) + "\t" + reading + "\n");
                        count++;
                    }
                }
            }
            if (count < 40_000) throw new IllegalStateException("Incomplete ICU Han-Latin source table");
            int contexts = 0;
            for (String line : han.toRules(true).split("\\R")) {
                if (!line.startsWith("{")) continue;
                var rule = CONTEXT_RULE.matcher(line);
                if (!rule.matches()) throw new IllegalStateException("Unexpected ICU contextual rule");
                int current = Integer.parseInt(rule.group(1), 16);
                int next = Integer.parseInt(rule.group(2), 16);
                String suffix = reading(normal, Character.toString(next));
                String phrase = reading(normal, Character.toString(current) + Character.toString(next));
                if (!phrase.endsWith(suffix) || phrase.length() <= suffix.length()) {
                    throw new IllegalStateException("Unexpected ICU contextual output");
                }
                String prefix = phrase.substring(0, phrase.length() - suffix.length());
                writer.write(Integer.toHexString(current) + "\t" + Integer.toHexString(next) + "\t" + prefix + "\n");
                contexts++;
            }
            if (contexts != 4) throw new IllegalStateException("Unexpected ICU Han-Latin contextual rule count");
        }
        try (var input = new JarFile(args[1]);
                var license = input.getInputStream(input.getJarEntry("LICENSE"))) {
            Files.copy(license, output.resolve("icu4j-license.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String reading(Transliterator transliterator, String value) {
        return SEPARATORS.matcher(transliterator.transliterate(value)).replaceAll("");
    }
}
