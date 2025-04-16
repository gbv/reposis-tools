<?xml version="1.0"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:pica2mods="http://www.mycore.org/pica2mods/xsl/functions"
                xmlns:p="info:srw/schema/5/picaXML-v1.0"
                xmlns:mods="http://www.loc.gov/mods/v3"
                xmlns:xlink="http://www.w3.org/1999/xlink"
                xmlns:temp="urn:temp-linking"
                exclude-result-prefixes="mods pica2mods p xlink temp">

    <xsl:param name="MCR.PICA2MODS.DATABASE" select="'k10plus'" />

    <xsl:template name="modsRelatedItem">
        <xsl:apply-templates mode="issnLink"
                             select="./p:datafield[@tag='039B' and count(./p:subfield[@code='i'] ='Enthalten in')&gt;0 and count(./p:subfield[@code='C'] = 'ISSN')&gt;0]"/>
        <xsl:apply-templates mode="isbnLink"
                             select="./p:datafield[@tag='039B' and count(./p:subfield[@code='i'] ='Enthalten in')&gt;0 and count(./p:subfield[@code='C'] = 'ISBN')&gt;0]"/>
        <xsl:apply-templates mode="rezensionLink"
                             select="./p:datafield[@tag='039B' and count(./p:subfield[@code='i'] ='Rezension von')&gt;0 and count(./p:subfield[@code='9'] = '000034525')&gt;0]"/>
    </xsl:template>

    <xsl:template match="p:datafield" mode="issnLink">
        <xsl:variable name="issn"
                      select="./p:subfield[@code='C' and text() = 'ISSN']/following-sibling::p:subfield[@code='6'][1]/text()"/>
        <xsl:if test="string-length($issn) &gt; 0">
            <mods:relatedItem temp:relatedISSN="{$issn}"/>
        </xsl:if>
    </xsl:template>

    <xsl:template match="p:datafield" mode="isbnLink">
        <xsl:variable name="isbn"
                      select="./p:subfield[@code='C' and text() = 'ISBN']/following-sibling::p:subfield[@code='6'][1]/text()"/>
        <xsl:if test="string-length($isbn) &gt; 0">
            <mods:relatedItem temp:relatedISBN="{$isbn}"/>
        </xsl:if>
    </xsl:template>

    <xsl:template match="p:datafield" mode="rezensionLink">
        <xsl:variable name="rezensionPPN" select="./p:subfield[@code='9']/text()"/>
        <xsl:if test="string-length($rezensionPPN) &gt; 0">
            <mods:relatedItem temp:relatedPPN="{$rezensionPPN}"/>
        </xsl:if>
    </xsl:template>


</xsl:stylesheet>
