package de.agentcodi.core;

/** Read-only, on-demand report evaluated by the current terminal shell. */
public final class PackageDiagnosticsCommand {
    private PackageDiagnosticsCommand() {
    }

    public static String create() {
        // A subshell keeps report variables out of the interactive session. Only
        // the managed prefix database is queried; PATH fallbacks are shown apart.
        // No package installation, configuration, auth data or network access.
        // unset is a special builtin. Clear a shadowing command function only
        // inside the subshell, then bypass helper aliases/functions with command.
        // Positional arguments avoid inheriting readonly/integer scratch variables.
        return "( \\unset -f command; "
            + "\\command printf 'Package paths\\nPREFIX=%s\\nHOME=%s\\nTMPDIR=%s\\nPATH=%s\\nLD_LIBRARY_PATH=%s\\nNPM_CONFIG_PREFIX=%s\\nXDG_CACHE_HOME=%s\\n' \"${PREFIX:-<unset>}\" \"${HOME:-<unset>}\" \"${TMPDIR:-<unset>}\" \"${PATH:-<unset>}\" \"${LD_LIBRARY_PATH:-<unset>}\" \"${NPM_CONFIG_PREFIX:-<unset>}\" \"${XDG_CACHE_HOME:-<unset>}\"; "
            + "\\command printf '\\nResolved commands (not proof of an APT installation)\\n'; "
            + "\\command set -- apt dpkg dpkg-query node npm npx python pip git rg; "
            + "while \\command [ \"$#\" -gt 0 ]; "
            + "do \\command printf '%s=%s\\n' \"$1\" \"$(\\command -v \"$1\" 2>/dev/null || \\command printf '<unavailable>')\"; "
            + "\\command shift; "
            + "done; "
            + "\\command printf '\\nManaged packages: name / version / state / status\\n'; "
            + "if \\command [ -z \"${PREFIX:-}\" ]; "
            + "then \\command printf 'Package prefix is unavailable.\\n'; "
            + "elif \\command [ ! -r \"$PREFIX/var/lib/dpkg/status\" ]; "
            + "then \\command printf 'Package database is unavailable.\\n'; "
            + "elif \\command [ ! -x \"$PREFIX/bin/dpkg-query\" ]; "
            + "then \\command printf 'Package query tool is unavailable.\\n'; "
            + "else \\command printf 'Database=%s/var/lib/dpkg\\n' \"$PREFIX\"; "
            + "LC_ALL=C \"$PREFIX/bin/dpkg-query\" --admindir=\"$PREFIX/var/lib/dpkg\" -W -f='${Package}\\t${Version}\\t${db:Status-Status}\\t${Status}\\n' || { \\command printf 'Package query failed.\\n'; "
            + "\\command exit 1; "
            + "}; "
            + "fi )\n";
    }
}
