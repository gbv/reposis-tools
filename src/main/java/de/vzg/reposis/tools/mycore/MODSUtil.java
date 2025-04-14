package de.vzg.reposis.tools.mycore;


import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;


public class MODSUtil {

    public static final Namespace XLINK_NAMESPACE = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    public static final Namespace MODS_NAMESPACE = Namespace.getNamespace("mods", "http://www.loc.gov/mods/v3");

    public static final String METADATA_XPATH = "/mycoreobject/metadata";

    private static final String LOCKED_DELETED_XPATH = "/mycoreobject/service/servstates/servstate[@categid='blocked' or @categid='deleted']";

    private static final String CREATED_BY_XPATH = "/mycoreobject/service/servflags/servflag[@type='createdby']";

    public static final String MODS_ELEMENT_XPATH = "/mycoreobject/metadata/def.modsContainer/modsContainer/mods:mods";

    public static final String MODS_FROM_METADATA_ELEMENT_XPATH = "/metadata/def.modsContainer/modsContainer/mods:mods";

    private static final String STATE_XPATH = "/mycoreobject/service/servstates/servstate";

    private static final String MODIFY_DATE_XPATH
        = "/mycoreobject/service/servdates[@class='MCRMetaISO8601Date']/servdate[@type='modifydate']";
    private static final String CREATE_DATE_XPATH
        = "/mycoreobject/service/servdates[@class='MCRMetaISO8601Date']/servdate[@type='createdate']";
    private static final String RECORD_INFO_XPATH
        = "/mycoreobject/metadata/def.modsContainer/modsContainer/mods:mods/mods:recordInfo";

    private static final String PARENT_XPATH = "/mycoreobject/structure/parents/parent";

    private static final String CHILDREN_XPATH = "/mycoreobject/structure/children/child";
    private static final String IDENTIFIER_XPATH
        = "/mycoreobject/metadata/def.modsContainer/modsContainer/mods:mods/mods:identifier";

    private static final String[] ORDER = { "genre", "typeofResource", "titleInfo", "nonSort", "subTitle", "title",
            "partNumber", "partName", "name", "namePart", "displayForm", "role", "affiliation", "originInfo", "place",
            "publisher", "dateIssued", "dateCreated", "dateModified", "dateValid", "dateOther", "edition", "issuance",
            "frequency", "relatedItem", "identifier", "language", "physicalDescription", "abstract", "note", "subject",
            "classification", "location", "shelfLocator", "url", "accessCondition", "part", "extension",
            "recordInfo", };

    private static final List<String> ORDER_LIST = List.of(ORDER);

    public static List<String> getChildren(Document mycoreObject) {
        XPathExpression<Element> childrenXPath = XPathFactory.instance().compile(CHILDREN_XPATH, Filters.element());
        return childrenXPath.evaluate(mycoreObject).stream()
            .map(child -> child.getAttributeValue("href", XLINK_NAMESPACE))
            .collect(Collectors.toList());
    }



    public static String getID(Document mycoreObject) {
        return mycoreObject.getRootElement().getAttributeValue("ID");
    }

    public static OffsetDateTime getLastModified(Document mycoreObject) {
        XPathExpression<Element> modifyDateXP
            = XPathFactory.instance().compile(MODIFY_DATE_XPATH, Filters.element(), null, MODS_NAMESPACE);
        final Element element = modifyDateXP.evaluateFirst(mycoreObject);
        if (element == null) {
            return null;
        }
        String text = element.getText();
        return Instant.parse(text).atOffset(ZoneOffset.UTC);
    }

    public static OffsetDateTime getCreateDate(Document mycoreObject) {
        XPathExpression<Element> createDateXP
            = XPathFactory.instance().compile(CREATE_DATE_XPATH, Filters.element(), null, MODS_NAMESPACE);
        final Element element = createDateXP.evaluateFirst(mycoreObject);
        if (element == null) {
            return null;
        }
        String text = element.getText();
        return Instant.parse(text).atOffset(ZoneOffset.UTC);
    }

    public static String getCreatedBy(Document createdBy) {
        XPathExpression<Element> createdByXPath
            = XPathFactory.instance().compile(CREATED_BY_XPATH, Filters.element(), null, MODS_NAMESPACE);
        final Element element = createdByXPath.evaluateFirst(createdBy);
        if (element == null) {
            return null;
        }
        return element.getText();
    }

    public static String getParent(Document mycoreObject) {
        XPathExpression<Element> parentXPath = XPathFactory.instance().compile(PARENT_XPATH, Filters.element());
        final Element element = parentXPath.evaluateFirst(mycoreObject);
        if (element == null) {
            return null;
        }
        return element.getAttributeValue("href", XLINK_NAMESPACE);
    }

    public static String getState(Document mycoreObject) {
        XPathExpression<Element> stateXPath = XPathFactory.instance().compile(STATE_XPATH, Filters.element());
        final Element element = stateXPath.evaluateFirst(mycoreObject);
           if (element == null) {
                return null;
            }
        return element.getAttributeValue("categid");
    }

    public static boolean setState(Document mycoreObject, String state) {
        XPathExpression<Element> stateXPath = XPathFactory.instance().compile(STATE_XPATH, Filters.element());
        final Element element = stateXPath.evaluateFirst(mycoreObject);
           if (element == null) {
                return false;
            }
           if(element.getAttributeValue("categid").equals(state)) {
               return false;
           }
        element.setAttribute("categid", state);
        return true;
    }

    public static MODSRecordInfo getRecordInfo(Document mycoreObject) {
        XPathExpression<Element> recordInfoXPath
            = XPathFactory.instance().compile(RECORD_INFO_XPATH, Filters.element(), null, MODS_NAMESPACE);
        final Element element = recordInfoXPath.evaluateFirst(mycoreObject);
        if (element == null) {
            return null;
        }
        String id = element.getChildTextTrim("recordIdentifier", MODS_NAMESPACE);
        String url = element.getChildTextTrim("recordContentSource", MODS_NAMESPACE);
        return new MODSRecordInfo(id, url);
    }

    public static boolean isLockedOrDeleted(Document childDoc) {
        XPathExpression<Element> fulltextXPath = XPathFactory.instance()
            .compile(LOCKED_DELETED_XPATH, Filters.element(), null, MODS_NAMESPACE);
        return fulltextXPath.evaluate(childDoc).size() > 0;
    }

    public static Element getMetadata(Document mycoreObject) {
        XPathExpression<Element> metadataXPath = XPathFactory.instance()
            .compile(METADATA_XPATH, Filters.element(), null, MODS_NAMESPACE);
        return metadataXPath.evaluateFirst(mycoreObject);
    }

    public static org.jdom2.Document wrapInMyCoReFrame(String xmlAsString, String baseID, String status) {
        SAXBuilder builder = new SAXBuilder();
        Element modsDoc;

        try (StringReader sr = new StringReader(xmlAsString)) {
            org.jdom2.Document document = builder.build(sr);
            modsDoc = document.getRootElement().detach();
        } catch (IOException | JDOMException e) {
            throw new RuntimeException(e);
        }

        Element mycoreDoc = new Element("mycoreobject");
        mycoreDoc.addContent(new Element("structure"));

        Element metadata = new Element("metadata");
        Element modsContainer = new Element("def.modsContainer")
                .setAttribute("class", "MCRMetaXML")
                .setAttribute("heritable", false + "")
                .setAttribute("notinherit", true + "");
        metadata.addContent(modsContainer);

        modsContainer.addContent(new Element("modsContainer").setAttribute("inherited", "0").addContent(modsDoc));

        mycoreDoc.addContent(metadata);

        Element service = new Element("service");
        Element servstates = new Element("servstates");
        service.addContent(servstates);
        servstates.setAttribute("class", "MCRMetaClassification");
        Element servState = new Element("servstate").setAttribute("categid", status)
                .setAttribute("classid", "state")
                .setAttribute("inherited", "0");

        servstates.addContent(servState);

        mycoreDoc.addContent(service);

        mycoreDoc.setAttribute("ID", baseID);

        return new org.jdom2.Document(mycoreDoc);
    }

    public static void setRecordInfo(Document document, String foreignId, String configId) {
        XPathExpression<Element> recordInfoXPath
            = XPathFactory.instance().compile(RECORD_INFO_XPATH, Filters.element(), null, MODS_NAMESPACE);
        final Element element = recordInfoXPath.evaluateFirst(document);
        if (element == null) {
            return;
        }
        Element recordIdentifier = element.getChild("recordIdentifier", MODS_NAMESPACE);
        if(recordIdentifier != null) {
            recordIdentifier.setText(foreignId);
        } else {
            element.addContent(new Element("recordIdentifier", MODS_NAMESPACE).setText(foreignId));
        }


        Element recordContentSource = element.getChild("recordContentSource", MODS_NAMESPACE);
        if(recordContentSource != null) {
            recordContentSource.setText(configId);
        } else {
            element.addContent(new Element("recordContentSource", MODS_NAMESPACE).setText(configId));
        }
    }

    public static void insertIdentifiers(Document metadataElement, List<Element> identifiers) {
        XPathExpression<Element> modsXPath = XPathFactory.instance()
            .compile(MODS_FROM_METADATA_ELEMENT_XPATH, Filters.element(), null, MODS_NAMESPACE);
        Element modsElement = modsXPath.evaluateFirst(metadataElement);
        if (modsElement != null) {

            for (Element identifier : identifiers) {
                boolean exists = modsElement.getChildren("identifier", MODS_NAMESPACE).stream()
                    .anyMatch(existingIdentifier -> existingIdentifier.getText().equals(identifier.getText()) &&
                        existingIdentifier.getAttributeValue("type").equals(identifier.getAttributeValue("type")));

                if (!exists) {
                    modsElement.addContent(identifier.clone());
                }
            }
        }
    }



    public static void sortMODSInMyCoreObject(Document mycoreObject) {
        XPathExpression<Element> modsXPath = XPathFactory.instance()
                .compile(MODS_ELEMENT_XPATH, Filters.element(), null, MODS_NAMESPACE);
        Element modsElement = modsXPath.evaluateFirst(mycoreObject);
        if (modsElement != null) {
            sortMODSElement(modsElement);
        }
    }

    public static void sortMODSInMetadataElement(Document metadataElement) {
        XPathExpression<Element> modsXPath = XPathFactory.instance()
                .compile(MODS_FROM_METADATA_ELEMENT_XPATH, Filters.element(), null, MODS_NAMESPACE);
        Element modsElement = modsXPath.evaluateFirst(metadataElement);
        if (modsElement != null) {
            sortMODSElement(modsElement);
        }
    }

    public static void sortMODSElement(Element mods) {
        mods.sortChildren(MODSUtil::compare);
    }

    private static int compare(Element e1, Element e2) {
        int pos1 = getPos(e1);
        int pos2 = getPos(e2);

        if (pos1 == pos2) {
            return e1.getName().compareTo(e2.getName());
        } else {
            return pos1 - pos2;
        }
    }

    private static int getPos(Element e) {
        String name = e.getName();
        return ORDER_LIST.contains(name) ? ORDER_LIST.indexOf(name) : ORDER_LIST.size();
    }

    public record MODSRecordInfo(String id, String url) {
    }
}
