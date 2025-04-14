package de.vzg.reposis.tools.pica;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PicaRecord {
    private final List<PicaField> fields;

    public PicaRecord() {
        this.fields = new ArrayList<>();
    }

    public void addField(PicaField field) {
        this.fields.add(field);
    }

    public List<PicaField> getFields() {
        return fields;
    }

     @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PicaRecord that = (PicaRecord) o;
        return Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fields);
    }

    @Override
    public String toString() {
        return "PicaRecord{" +
               "fields=" + fields +
               '}';
    }
}
