# Plain JSON for Windows PowerShell 5.1. ConvertTo-Json walks strings and runs out of memory.
if (-not ("OipDemoJson" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections;
using System.Globalization;
using System.Management.Automation;
using System.Text;
public static class OipDemoJson {
    public static string Write(object value) {
        var sb = new StringBuilder();
        WriteValue(value, sb);
        return sb.ToString();
    }
    static object Unwrap(object value) {
        var wrapped = value as PSObject;
        return wrapped == null ? value : Unwrap(wrapped.BaseObject);
    }
    static void WriteValue(object value, StringBuilder sb) {
        value = Unwrap(value);
        if (value == null) { sb.Append("null"); return; }
        var text = value as string;
        if (text != null) { WriteString(text, sb); return; }
        if (value is bool) { sb.Append((bool)value ? "true" : "false"); return; }
        var map = value as IDictionary;
        if (map != null) {
            sb.Append('{');
            var first = true;
            foreach (DictionaryEntry entry in map) {
                if (!first) sb.Append(',');
                first = false;
                WriteString(Convert.ToString(entry.Key, CultureInfo.InvariantCulture), sb);
                sb.Append(':');
                WriteValue(entry.Value, sb);
            }
            sb.Append('}');
            return;
        }
        var list = value as IEnumerable;
        if (list != null) {
            sb.Append('[');
            var first = true;
            foreach (var item in list) {
                if (!first) sb.Append(',');
                first = false;
                WriteValue(item, sb);
            }
            sb.Append(']');
            return;
        }
        var number = value as IFormattable;
        if (number != null && (value is byte || value is short || value is int || value is long || value is float || value is double || value is decimal)) {
            sb.Append(number.ToString(null, CultureInfo.InvariantCulture));
            return;
        }
        WriteString(Convert.ToString(value, CultureInfo.InvariantCulture), sb);
    }
    static void WriteString(string text, StringBuilder sb) {
        sb.Append('"');
        foreach (var ch in text) {
            switch (ch) {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (ch < ' ') sb.Append("\\u").Append(((int)ch).ToString("x4"));
                    else sb.Append(ch);
                    break;
            }
        }
        sb.Append('"');
    }
}
"@ -ReferencedAssemblies "System.Management.Automation.dll"
}

function ConvertTo-Body($Body) {
    if ($null -eq $Body) { return "" }
    if ($Body -is [string]) { return $Body }
    return [OipDemoJson]::Write($Body)
}
