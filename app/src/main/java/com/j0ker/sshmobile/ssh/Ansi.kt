package com.j0ker.sshmobile.ssh

/**
 * Port of `TerminalPanel.StripAnsi`. The desktop client pushed shell output
 * into a RichTextBox, which garbles on VT100 escapes; a Compose Text has the
 * same problem, so the same stripping applies.
 */
fun stripAnsi(input: String): String {
    val sb = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == ESC && i + 1 < input.length && input[i + 1] == '[') {
            // Skip ESC [ ... <letter>
            i += 2
            while (i < input.length && input[i] !in 'A'..'Z' && input[i] !in 'a'..'z') i++
            i++ // skip terminating letter
        } else if (c == ESC && i + 1 < input.length && input[i + 1] == ']') {
            // OSC: ESC ] ... BEL, or ESC ] ... ESC \. Common for title setting,
            // which the desktop's simpler loop left as visible junk.
            i += 2
            while (i < input.length && input[i] != BEL) {
                if (input[i] == ESC && i + 1 < input.length && input[i + 1] == '\\') { i++; break }
                i++
            }
            i++
        } else if (c == ESC && i + 1 < input.length) {
            // Other escape sequences (e.g. ESC c, ESC =)
            i += 2
        } else if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') {
            sb.append('\n')
            i += 2
        } else if (c == '\b') {
            // Backspace: the shell echoes these while editing a line.
            if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
            i++
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private val ESC = 27.toChar()
private val BEL = 7.toChar()
