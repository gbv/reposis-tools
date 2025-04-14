package de.vzg.reposis.tools.pica;

import java.util.Objects;

public class PicaSubfield {
    private final char code;
    private final String value;

    public PicaSubfield(char code, String value) {
        this.code = code;
        this.value = value;
    }

    public char getCode() {
        return code;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PicaSubfield that = (PicaSubfield) o;
        return code == that.code && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, value);
    }

    @Override
    public String toString() {
        return "PicaSubfield{" +
               "code=" + code +
               ", value='" + value + '\'' +
               '}';
    }
}
