package de.vzg.reposis.tools.pica;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PicaField {
    private final String tag;
    private final String occurrence;
    private final List<PicaSubfield> subfields;

    public PicaField(String tag, String occurrence) {
        this.tag = tag;
        this.occurrence = occurrence;
        this.subfields = new ArrayList<>();
    }

    public void addSubfield(PicaSubfield subfield) {
        this.subfields.add(subfield);
    }

    public String getTag() {
        return tag;
    }

    public String getOccurrence() {
        return occurrence;
    }

    public List<PicaSubfield> getSubfields() {
        return subfields;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PicaField picaField = (PicaField) o;
        return Objects.equals(tag, picaField.tag) && Objects.equals(occurrence, picaField.occurrence) && Objects.equals(subfields, picaField.subfields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, occurrence, subfields);
    }

    @Override
    public String toString() {
        return "PicaField{" +
               "tag='" + tag + '\'' +
               ", occurrence='" + occurrence + '\'' +
               ", subfields=" + subfields +
               '}';
    }
}
