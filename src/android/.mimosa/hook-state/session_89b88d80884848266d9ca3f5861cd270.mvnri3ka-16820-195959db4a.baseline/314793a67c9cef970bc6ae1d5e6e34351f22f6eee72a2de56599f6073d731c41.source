package javax.lang.model;

/**
 * Rhino Android compatibility stub.
 *
 * Rhino 1.7.14 {@code JavaMembers} static init references
 * {@code javax.lang.model.SourceVersion}. That package is not on the ART
 * bootclasspath, so {@code Context.javaToJS()} dies with
 * {@code NoClassDefFoundError} before any script runs. This type is packaged
 * into the APK dex. It does not conflict with the bootclasspath.
 *
 * {@code isIdentifier} / {@code isName} / {@code isKeyword} follow OpenJDK 17
 * {@code jdk17u} {@code javax.lang.model.SourceVersion} (not a constant
 * {@code true}). Rhino uses them to accept or reject Java member names; a
 * stub that always returns true would let illegal identifiers through.
 *
 * {@link #latestSupported()} returns {@link #RELEASE_8}. Rhino 1.7.14 treats
 * {@code latestSupported().ordinal() > 8} as "use {@code JavaMembers_jdk11}",
 * which assumes {@code java.lang.Module}. Android has no module API, so this
 * stub stays on the classic reflector. Do not call {@code Runtime.version()}
 * here — that method is missing on many ART releases and would recreate the
 * clinit crash.
 *
 * Delete this file when execute_code no longer loads Rhino's {@code JavaMembers}
 * (no {@code javaToJS} / {@code WrapFactory} reflection into JDK compiler APIs).
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
    RELEASE_12,
    RELEASE_13,
    RELEASE_14,
    RELEASE_15,
    RELEASE_16,
    RELEASE_17;

    public static SourceVersion latest() {
        return RELEASE_17;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.length() == 0) {
            return false;
        }
        int cp = id.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) {
            return false;
        }
        for (int i = Character.charCount(cp); i < id.length(); i += Character.charCount(cp)) {
            cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        return isName(name, latest());
    }

    public static boolean isName(CharSequence name, SourceVersion version) {
        String id = name.toString();
        for (String s : id.split("\\.", -1)) {
            if (!isIdentifier(s) || isKeyword(s, version)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        return isKeyword(s, latest());
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        String id = s.toString();
        switch (id) {
            case "strictfp":
                return version.compareTo(RELEASE_2) >= 0;
            case "assert":
                return version.compareTo(RELEASE_4) >= 0;
            case "enum":
                return version.compareTo(RELEASE_5) >= 0;
            case "_":
                return version.compareTo(RELEASE_9) >= 0;
            case "public": case "protected": case "private":
            case "abstract": case "static": case "final":
            case "transient": case "volatile": case "synchronized":
            case "native":
            case "class": case "interface": case "extends":
            case "package": case "throws": case "implements":
            case "boolean": case "byte": case "char":
            case "short": case "int": case "long":
            case "float": case "double":
            case "void":
            case "if": case "else":
            case "try": case "catch": case "finally":
            case "do": case "while":
            case "for": case "continue":
            case "switch": case "case": case "default":
            case "break": case "throw": case "return":
            case "this": case "new": case "super":
            case "import": case "instanceof":
            case "goto": case "const":
            case "null": case "true": case "false":
                return true;
            default:
                return false;
        }
    }
}
