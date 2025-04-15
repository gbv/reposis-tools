<?xml version="1.0"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:pica2mods="http://www.mycore.org/pica2mods/xsl/functions"
                xmlns:p="info:srw/schema/5/picaXML-v1.0"
                xmlns:mods="http://www.loc.gov/mods/v3"
                xmlns:xlink="http://www.w3.org/1999/xlink"
                exclude-result-prefixes="mods pica2mods p xlink"
                expand-text="yes">

    <xsl:import use-when="system-property('XSL_TESTING')='true'" href="_common/pica2mods-functions.xsl" />

    <xsl:param name="MCR.PICA2MODS.DATABASE" select="'k10plus'" />

    <!-- This template is for testing purposes -->
    <xsl:template match="p:record">
        <mods:mods>
            <xsl:call-template name="modsGenre" />
        </mods:mods>
    </xsl:template>

    <xsl:template name="modsGenre">
        <xsl:variable name="genreID">
            <xsl:choose>
                <xsl:when test="./p:datafield[@tag='002@']/p:subfield[@code='0'] = 'Asu'">
                    <xsl:text>article</xsl:text>
                </xsl:when>
                <xsl:when test="./p:datafield[@tag='002@']/p:subfield[@code='0'] = 'Aau' and
                            ./p:datafield[@tag='037A']/p:subfield[@code='a' and
                            contains(., 'Unpublished Doctoral Dissertations /Higher Degree Theses')]">
                    <xsl:text>dissertation</xsl:text>
                </xsl:when>
                <xsl:when test="./p:datafield[@tag='002@']/p:subfield[@code='0'] = 'Aau'">
                    <xsl:text>monograph</xsl:text>">
                </xsl:when>
                <xsl:otherwise>
                    <xsl:message>WARNING: no genre found for <xsl:value-of select="./p:datafield[@tag='003@']//p:subfield[@code='0']"/></xsl:message>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:variable>
        <xsl:if test="string-length($genreID) &gt; 0">
            <mods:genre type="intern"
                        authorityURI="http://www.mycore.org/classifications/mir_genres"
                        valueURI="http://www.mycore.org/classifications/mir_genres#{$genreID}"/>
        </xsl:if>


    </xsl:template>


</xsl:stylesheet>
