<?xml version="1.0"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:pica2mods="http://www.mycore.org/pica2mods/xsl/functions"
                xmlns:p="info:srw/schema/5/picaXML-v1.0"
                xmlns:mods="http://www.loc.gov/mods/v3"
                xmlns:xlink="http://www.w3.org/1999/xlink"
                exclude-result-prefixes="mods pica2mods p xlink">

    <xsl:import use-when="system-property('XSL_TESTING')='true'" href="_common/pica2mods-functions.xsl" />
    <xsl:param name="MCR.PICA2MODS.DATABASE" select="'k10plus'" />
    <xsl:template name="modsRelatedItem">
        <xsl:for-each select="./p:datafield[@tag='039B']"> <!-- 4241 Beziehungen zur größeren Einheit -->
            <xsl:call-template name="COMMON_AppearsIn" />
        </xsl:for-each>

        <xsl:variable name="pica0500_2" select="substring(./p:datafield[@tag='002@']/p:subfield[@code='0'],2,1)" />
        <xsl:if test="not($pica0500_2 = 'b' or $pica0500_2 = 'd')">
            <xsl:for-each select="./p:datafield[@tag='036D']"> <!-- 4160 übergeordnetes Werk -->
                <xsl:call-template name="COMMON_HostOrSeries" />
            </xsl:for-each>
            <xsl:for-each select="./p:datafield[@tag='036F']"> <!-- 4180 Schriftenreihe, Zeitschrift -->
                <xsl:call-template name="COMMON_HostOrSeries" />
            </xsl:for-each>
            <xsl:for-each select="./p:datafield[@tag='036E' and not(../p:datafield[@tag='036F'])]"> <!-- 4180 Schriftenreihe, Zeitschrift ohne Od,Ob-Verknüpfung -->
                <xsl:call-template name="COMMON_HostOrSeries" />
            </xsl:for-each>
        </xsl:if>

        <xsl:for-each select="./p:datafield[@tag='039D' and p:subfield[@code='n'] = 'Verlags-Ausgabe']"> <!-- 4243 Beziehungen auf Manifestationsebene -->
            <xsl:call-template name="COMMON_Reference">
                <xsl:with-param name="type">otherFormat</xsl:with-param>
                <xsl:with-param name="datafield" select="." />
            </xsl:call-template>
        </xsl:for-each>
        <xsl:for-each select="./p:datafield[@tag='039P']"> <!-- 4261 Themenbeziehungen (Beziehung zu der Resource, die beschrieben wird) -->
            <xsl:call-template name="COMMON_Reference">
                <xsl:with-param name="type">references</xsl:with-param>
                <xsl:with-param name="datafield" select="." />
            </xsl:call-template>
        </xsl:for-each>
        <xsl:for-each select="./p:datafield[@tag='039Q']"> <!-- 4262 Themenbeziehungen (Beziehung zu einer Beschreibung über die Ressource) -->
            <xsl:call-template name="COMMON_Reference">
                <xsl:with-param name="type">isReferencedBy</xsl:with-param>
                <xsl:with-param name="datafield" select="." />
            </xsl:call-template>
        </xsl:for-each>
        <xsl:for-each select="./p:datafield[@tag='039N' and starts-with(p:subfield[@code='i'], 'Überarbeit')]"> <!-- 4261 Weitere Beziehungen -->
            <xsl:call-template name="COMMON_Reference">
                <xsl:with-param name="type">otherVersion</xsl:with-param>
                <xsl:with-param name="datafield" select="." />
            </xsl:call-template>
        </xsl:for-each>

        <!-- 4244 Vorgänger-Nachfolge-Beziehungen auf Werkebene [nur für Ob Sätze = Zeitschriften] -->
        <xsl:for-each select=".[starts-with(p:datafield[@tag='002@']/p:subfield[@code='0'],'Ob')]/p:datafield[@tag='039E']">
            <xsl:choose>
                <xsl:when test="./p:subfield[@code='b' and text()='f']">
                    <xsl:call-template name="COMMON_Reference">
                        <xsl:with-param name="type">preceding</xsl:with-param>
                        <xsl:with-param name="otherType">chronological</xsl:with-param>
                        <xsl:with-param name="datafield" select="." />
                    </xsl:call-template>
                </xsl:when>
                <xsl:when test="./p:subfield[@code='b' and text()='s']">
                    <xsl:call-template name="COMMON_Reference">
                        <xsl:with-param name="type">succeeding</xsl:with-param>
                        <xsl:with-param name="otherType">chronological</xsl:with-param>
                        <xsl:with-param name="datafield" select="." />
                    </xsl:call-template>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:call-template name="COMMON_Reference">
                        <xsl:with-param name="otherType">chronological</xsl:with-param>
                        <xsl:with-param name="datafield" select="." />
                    </xsl:call-template>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:for-each>
    </xsl:template>

    <xsl:template name="COMMON_HostOrSeries">
        <mods:relatedItem>
            <xsl:variable name="parent" select="pica2mods:queryPicaFromUnAPIWithPPN($MCR.PICA2MODS.DATABASE, ./p:subfield[@code='9'])" />
            <xsl:variable name="parentPica0500_2" select="substring($parent/p:datafield[@tag='002@']/p:subfield[@code='0'],2,1)" />
            <xsl:choose>
                <xsl:when test="./@tag='036C'"> <!-- 4150  Mehrbändiges Werk -->
                    <xsl:attribute name="type">host</xsl:attribute>
                </xsl:when>
                <xsl:when test="./@tag='036D'"> <!-- 4160  Mehrbändiges Werk -->
                    <xsl:attribute name="type">host</xsl:attribute>
                </xsl:when>
                <xsl:when test="./@tag='036E'"> <!-- 4170  fortlaufende Resource -->
                    <xsl:attribute name="type">series</xsl:attribute>
                </xsl:when>
                <xsl:when test="./@tag='036F' and $parentPica0500_2 = 'b'"> <!-- 4180  Zeitung / Zeitschrift-->
                    <xsl:attribute name="type">host</xsl:attribute>
                </xsl:when>
                <xsl:when test="./@tag='036F' and $parentPica0500_2 = 'd'"> <!-- 4180  Schriftenreihe -->
                    <xsl:attribute name="type">series</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:attribute name="otherType">hierarchical</xsl:attribute>

            <xsl:choose>
                <xsl:when test="$parent/*">
                    <xsl:call-template name="parent_info">
                        <xsl:with-param name="parent" select="$parent" />
                    </xsl:call-template>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:call-template name="simple_title">
                        <xsl:with-param name="datafield" select="." />
                    </xsl:call-template>
                </xsl:otherwise>
            </xsl:choose>

            <xsl:if test="../p:datafield[@tag='036C' or @tag='036D' or @tag='036E' or @tag='036F']">
                <mods:part>
                    <!-- set order attribute only if value of subfield $X is a number -->
                    <xsl:if test="./p:subfield[@code='X']">
                        <xsl:choose>
                            <!-- sort string contains 2 commas -->
                            <xsl:when
                                    test="string-length(./p:subfield[@code='X']) = string-length(translate(./p:subfield[@code='X'], ',','')) + 2">
                                <xsl:if test="number(translate(./p:subfield[@code='X'], ',',''))">
                                    <xsl:attribute name="order">
                                        <xsl:value-of select="format-number(number(translate(./p:subfield[@code='X'], ',','')), '#')" />
                                    </xsl:attribute>
                                </xsl:if>
                            </xsl:when>
                            <xsl:when test="contains(./p:subfield[@code='X'], ',')">
                                <xsl:if test="number(substring-before(substring-before(./p:subfield[@code='X'], '.'), ','))">
                                    <xsl:attribute name="order">
                                        <xsl:value-of select="format-number(number(substring-before(substring-before(./p:subfield[@code='X'], '.'), ',')), '#')" />
                                    </xsl:attribute>
                                </xsl:if>
                            </xsl:when>
                            <xsl:otherwise>
                                <xsl:if test="number(substring-before(./p:subfield[@code='X'], '.'))">
                                    <xsl:attribute name="order">
                                        <xsl:value-of select="format-number(number(substring-before(./p:subfield[@code='X'], '.')),'#')" />
                                    </xsl:attribute>
                                </xsl:if>
                            </xsl:otherwise>
                        </xsl:choose>
                    </xsl:if>

                    <xsl:choose>
                        <xsl:when test="./p:subfield[@code='l' or @code='p'] or (../p:datafield[@tag='021A'] and (@tag='036C' or @tag='036D'))">
                            <mods:detail type="volume">
                                <xsl:if test="./p:subfield[@code='l' or @code='p']">
                                    <xsl:choose>
                                        <xsl:when test="@tag='036F' and ../p:datafield[@tag='036E']">
                                            <mods:number>
                                                <xsl:value-of select="string-join(../p:datafield[@tag='036E']/p:subfield[@code='l' or @code='p' or @code='m'], ', ')" />
                                            </mods:number>
                                            <xsl:comment>[alternativ aus 4180: <xsl:value-of select="./p:subfield[@code='l']" />]</xsl:comment>
                                        </xsl:when>
                                        <xsl:when test="@tag='036D' and ../p:datafield[@tag='036C']">
                                            <mods:number>
                                                <xsl:value-of select="string-join(../p:datafield[@tag='036C']/p:subfield[@code='l' or @code='p' or @code='m'], ', ')" />
                                            </mods:number>
                                            <xsl:comment>[alternativ aus 4160: <xsl:value-of select="./p:subfield[@code='l']" />]</xsl:comment>
                                        </xsl:when>
                                        <xsl:otherwise>
                                            <mods:number>
                                                <xsl:value-of select="string-join(./p:subfield[@code='l' or @code='p' or @code='m'], ', ')" />
                                            </mods:number>
                                        </xsl:otherwise>
                                    </xsl:choose>
                                </xsl:if>
                                <xsl:if test="../p:datafield[@tag='021A'] and (@tag='036C' or @tag='036D')">
                                    <!-- Title nur für MBW -->
                                    <mods:title>
                                        <xsl:value-of select="replace(string-join(../p:datafield[@tag='021A']/p:subfield[@code='a' or @code='d'], ' : '), '@', '')" />
                                    </mods:title>
                                </xsl:if>
                            </mods:detail>
                        </xsl:when>
                        <xsl:otherwise>
                            <mods:detail type="volume">
                                <mods:caption>[uncounted]</mods:caption>
                            </mods:detail>
                        </xsl:otherwise>
                    </xsl:choose>

                    <xsl:if test="(@tag='036D' or @tag='036F') and ./p:subfield[@code='X']"> <!-- 4160, 4180 -->
                        <mods:text type="sortstring">
                            <xsl:value-of select="pica2mods:sortableSortstring(./p:subfield[@code='X'])" />
                        </mods:text>
                    </xsl:if>
                </mods:part>
            </xsl:if>
        </mods:relatedItem>
    </xsl:template>

    <xsl:template name="COMMON_AppearsIn">
        <xsl:variable name="pica0500_2" select="substring(./../p:datafield[@tag='002@']/p:subfield[@code='0'],2,1)" />
        <xsl:variable name="parent" select="pica2mods:queryPicaFromUnAPIWithPPN($MCR.PICA2MODS.DATABASE, ./p:subfield[@code='9'])" />

        <xsl:choose>
            <xsl:when test="$pica0500_2='s'">
                <mods:relatedItem>
                    <xsl:attribute name="otherType">appears_in</xsl:attribute>
                    <xsl:attribute name="type">host</xsl:attribute>
                    <mods:part>
                        <xsl:for-each select="./../p:datafield[@tag='031A']"> <!-- 4070 Differenzierende Angaben zur Quelle -->
                            <xsl:if test="./p:subfield[@code='d']">
                                <mods:detail type="volume">
                                    <mods:number>
                                        <xsl:value-of select="./p:subfield[@code='d']" />
                                    </mods:number>
                                </mods:detail>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='e'] or ./p:subfield[@code='f']">
                                <mods:detail type="issue">
                                    <mods:number>
                                        <xsl:value-of select="./p:subfield[@code='e'] | ./p:subfield[@code='f']" />
                                    </mods:number>
                                </mods:detail>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='h' or @code='g']">
                                <mods:extent unit="pages">
                                    <xsl:choose>
                                        <xsl:when test="matches(./p:subfield[@code='h'],'^\[?[0-9]+\]?-\[?[0-9]+\]?$')">
                                            <mods:start><xsl:value-of select="substring-before(./p:subfield[@code='h'],'-')" /></mods:start>
                                            <mods:end><xsl:value-of select="substring-after(./p:subfield[@code='h'],'-')" /></mods:end>
                                        </xsl:when>
                                        <xsl:when test="matches(./p:subfield[@code='h'],'^[0-9]+$')">
                                            <mods:start><xsl:value-of select="./p:subfield[@code='h']" /></mods:start>
                                        </xsl:when>
                                    </xsl:choose>
                                    <xsl:if test="./p:subfield[@code='g']">
                                        <mods:total><xsl:value-of select="./p:subfield[@code='g']" /></mods:total>
                                    </xsl:if>
                                </mods:extent>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='i']">
                                <mods:detail type="article_number">
                                    <mods:number><xsl:value-of select="./p:subfield[@code='i']" /></mods:number>
                                </mods:detail>
                            </xsl:if>
                            <xsl:variable name="year" select="number(./p:subfield[@code='j'])"/>
                            <xsl:variable name="month" select="number(./p:subfield[@code='c'])"/>
                            <xsl:variable name="day" select="number(./p:subfield[@code='b'])"/>
                            <xsl:variable name="date">
                                <xsl:choose>
                                    <xsl:when test="$year and $month &gt; 0 and $month &lt; 13 and $day">
                                        <xsl:value-of select="concat(format-number($year, '0000'), '-', format-number($month, '00'), '-', format-number($day, '00'))"/>
                                    </xsl:when>
                                    <!-- month also can be 21-24, 33-36, 40 and 41 see: https://format.k10plus.de/k10plushelp.pl?cmd=kat&val=4070&katalog=Standard#$m -->
                                    <xsl:when test="$year and $month &gt; 0 and $month &lt; 13">
                                        <xsl:value-of select="concat(format-number($year, '0000'), '-', format-number($month, '00'))"/>
                                    </xsl:when>
                                    <xsl:when test="$year">
                                        <xsl:value-of select="format-number($year, '0000')"/>
                                    </xsl:when>
                                </xsl:choose>
                            </xsl:variable>
                            <xsl:if test="string-length($date)&gt;0">
                                <mods:date encoding="w3cdtf">
                                    <xsl:value-of select="$date"/>
                                </mods:date>
                            </xsl:if>
                        </xsl:for-each>
                        <xsl:if test="./p:subfield[@code='x']">
                            <mods:text type="sortstring">
                                <xsl:value-of select="pica2mods:sortableSortstring(./p:subfield[@code='x'])" />
                            </mods:text>
                        </xsl:if>
                        <xsl:if test="./p:subfield[@code='y']">
                            <mods:text type="display">
                                <xsl:value-of select="./p:subfield[@code='y']" />
                            </mods:text>
                        </xsl:if>
                    </mods:part>
                    <xsl:call-template name="parent_info">
                        <xsl:with-param name="parent" select="$parent" />
                    </xsl:call-template>
                    <xsl:if test="./p:subfield[@code='i']">
                        <mods:note type="relation_label"><xsl:value-of select="./p:subfield[@code='i']" /></mods:note>
                    </xsl:if>
                </mods:relatedItem>
            </xsl:when>
            <xsl:when test="$pica0500_2='a'">
                <mods:relatedItem>
                    <xsl:attribute name="otherType">appears_in</xsl:attribute>
                    <xsl:choose>
                        <xsl:when test="$parent/p:datafield">
                            <xsl:call-template name="parent_info">
                                <xsl:with-param name="parent" select="$parent" />
                            </xsl:call-template>
                        </xsl:when>
                        <xsl:otherwise>
                            <xsl:if test="./p:subfield[@code='t']">
                                <xsl:variable name="titlefield">
                                    <p:datafield tag="021A">
                                        <p:subfield code="a">
                                            <xsl:value-of select="./p:subfield[@code='t']/text()" />
                                        </p:subfield>
                                    </p:datafield>
                                </xsl:variable>
                                <xsl:call-template name="simple_title">
                                    <xsl:with-param name="datafield" select="$titlefield/p:datafield" />
                                </xsl:call-template>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='f' or @code='d' or @code='e']">
                                <mods:originInfo eventType="publication">
                                    <xsl:if test="./p:subfield[@code='f']">
                                        <mods:dateIssued keyDate="yes" encoding="w3cdtf">
                                            <xsl:value-of select="./p:subfield[@code='f']" />
                                        </mods:dateIssued>
                                    </xsl:if>
                                    <xsl:if test="./p:subfield[@code='e']">
                                        <mods:publisher><xsl:value-of select="./p:subfield[@code='e']" /></mods:publisher>
                                    </xsl:if>
                                    <xsl:if test="./p:subfield[@code='d']">
                                        <mods:place>
                                            <mods:placeTerm type="text"><xsl:value-of select="./p:subfield[@code='d']" /></mods:placeTerm>
                                        </mods:place>
                                    </xsl:if>
                                </mods:originInfo>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='p']">
                                <mods:part>
                                    <mods:detail type="article">
                                        <mods:number>
                                            <xsl:value-of select="./p:subfield[@code='p']" />
                                        </mods:number>
                                    </mods:detail>
                                </mods:part>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='l']">
                                <mods:name>
                                    <mods:displayForm>
                                        <xsl:value-of select="./p:subfield[@code='l']" />
                                    </mods:displayForm>
                                </mods:name>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='C' and text()='DOI']">
                                <mods:identifier type="doi">
                                    <xsl:value-of select="./p:subfield[@code='C' and text()='DOI']/following-sibling::p:subfield[@code='6'][1]" />
                                </mods:identifier>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='C' and text()='ISBN']">
                                <mods:identifier type="isbn">
                                    <xsl:value-of select="./p:subfield[@code='C' and text()='ISBN']/following-sibling::p:subfield[@code='6'][1]" />
                                </mods:identifier>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='C' and text()='ISSN']">
                                <mods:identifier type="issn">
                                    <xsl:value-of select="./p:subfield[@code='C' and text()='ISSN']/following-sibling::p:subfield[@code='6'][1]" />
                                </mods:identifier>
                            </xsl:if>
                            <xsl:if test="./p:subfield[@code='C' and text()='ZDB']">
                                <mods:identifier type="zdb">
                                    <xsl:value-of select="./p:subfield[@code='C' and text()='ZDB']/following-sibling::p:subfield[@code='6'][1]" />
                                </mods:identifier>
                            </xsl:if>
                        </xsl:otherwise>
                    </xsl:choose>
                    <xsl:if test="./p:subfield[@code='i']">
                        <mods:note type="relation_label"><xsl:value-of select="./p:subfield[@code='i']" /></mods:note>
                    </xsl:if>
                </mods:relatedItem>
            </xsl:when>
        </xsl:choose>

    </xsl:template>

    <xsl:template name="COMMON_Reference">
        <xsl:param name="type" required="no" />
        <xsl:param name="otherType" required="no" />
        <xsl:param name="datafield" />
        <xsl:comment>COMMON_REFERENCE for <xsl:value-of select="$datafield/@tag" /></xsl:comment>
        <mods:relatedItem>
            <xsl:if test="$type">
                <xsl:attribute name="type" select="$type" />
            </xsl:if>
            <xsl:if test="$otherType">
                <xsl:attribute name="otherType" select="$otherType" />
            </xsl:if>
            <xsl:choose>
                <xsl:when test="$datafield/p:subfield[@code='9']">
                    <xsl:variable name="parent" select="pica2mods:queryPicaFromUnAPIWithPPN($MCR.PICA2MODS.DATABASE, ./p:subfield[@code='9'])" />
                    <xsl:if test="starts-with($parent/p:datafield[@tag='002@']/p:subfield[@code='0'], 'O')">
                        <xsl:call-template name="parent_info">
                            <xsl:with-param name="parent" select="$parent" />
                        </xsl:call-template>
                        <xsl:choose>
                            <xsl:when test="$parent/p:datafield[@tag='004V']">
                                <mods:identifier type='doi'><xsl:value-of select="$parent/p:datafield[@tag='004V']/p:subfield[@code='0']" /></mods:identifier>
                            </xsl:when>
                            <xsl:when test="$parent/p:datafield[@tag='017C']">
                                <mods:identifier type='url'><xsl:value-of select="$parent/p:datafield[@tag='017C'][1]/p:subfield[@code='u']" /></mods:identifier>
                            </xsl:when>
                        </xsl:choose>
                        <xsl:if test="$parent[starts-with(p:datafield[@tag='002@']/p:subfield[@code='0'], 'Ob')]/p:datafield[@tag='006Z']">
                            <mods:identifier type="zdb">
                                <xsl:value-of select="$parent/p:datafield[@tag='006Z']/p:subfield[@code='0']" />
                            </mods:identifier>
                        </xsl:if>
                    </xsl:if>
                </xsl:when>
                <xsl:otherwise>
                    <!-- <xsl:when test="$datafield/p:subfield[@code='C' and text()='DOI']"> -->
                    <xsl:if test="$datafield/p:subfield[@code='a']">
                        <xsl:variable name="titlefield">
                            <p:datafield tag="021A">
                                <xsl:copy-of select="$datafield/p:subfield[@code='a']" />
                            </p:datafield>
                        </xsl:variable>
                        <xsl:call-template name="simple_title">
                            <xsl:with-param name="datafield" select="$titlefield/p:datafield" />
                        </xsl:call-template>
                    </xsl:if>
                    <xsl:if test="$datafield/p:subfield[@code='t']">
                        <xsl:variable name="titlefield">
                            <p:datafield tag="021A">
                                <p:subfield code="a">
                                    <xsl:value-of select="$datafield/p:subfield[@code='t']/text()" />
                                </p:subfield>
                            </p:datafield>
                        </xsl:variable>
                        <xsl:call-template name="simple_title">
                            <xsl:with-param name="datafield" select="$titlefield/p:datafield" />
                        </xsl:call-template>
                    </xsl:if>
                    <xsl:if test="$datafield/p:subfield[@code='C' and text()='DOI']">
                        <mods:identifier type="doi">
                            <xsl:value-of select="$datafield/p:subfield[@code='C' and text()='DOI']/following-sibling::p:subfield[@code='6'][1]" />
                        </mods:identifier>
                    </xsl:if>
                </xsl:otherwise>
            </xsl:choose>
            <xsl:if test="$datafield/p:subfield[@code='i']">
                <mods:note type="relation_label"><xsl:value-of select="$datafield/p:subfield[@code='i']" /></mods:note>
            </xsl:if>
            <xsl:if test="$datafield[@tag='039D']/p:subfield[@code='n']">  <!-- 4243 039D Beziehung auf Manifestationsebene -->
                <mods:note type="format_type"><xsl:value-of select="$datafield[@tag='039D']/p:subfield[@code='n']" /></mods:note>
            </xsl:if>
        </mods:relatedItem>
    </xsl:template>


    <xsl:template name="simple_title">
        <xsl:param name="datafield" />
        <xsl:if test="$datafield/p:subfield[@code='a']">
            <mods:titleInfo>
                <xsl:variable name="mainTitle" select="$datafield[1]/p:subfield[@code='a'][1]" />
                <xsl:choose>
                    <xsl:when test="contains($mainTitle, '@')">
                        <xsl:variable name="nonSort" select="normalize-space(substring-before($mainTitle, '@'))" />
                        <xsl:choose>
                            <!-- nonSort this should be deadCode -->
                            <xsl:when test="string-length(nonSort) &lt; 9">
                                <mods:nonSort>
                                    <xsl:value-of select="$nonSort" />
                                </mods:nonSort>
                                <mods:title>
                                    <xsl:value-of select="substring-after($mainTitle, '@')" />
                                </mods:title>
                            </xsl:when>
                            <xsl:otherwise>
                                <mods:title>
                                    <xsl:value-of select="$mainTitle" />
                                </mods:title>
                            </xsl:otherwise>
                        </xsl:choose>
                    </xsl:when>
                    <xsl:otherwise>
                        <mods:title>
                            <xsl:value-of select="$mainTitle" />
                        </mods:title>
                    </xsl:otherwise>
                </xsl:choose>
                <xsl:if test="$datafield/p:subfield[@code='d']">
                    <mods:subTitle>
                        <xsl:value-of select="$datafield/p:subfield[@code='d']" />
                    </mods:subTitle>
                </xsl:if>
            </mods:titleInfo>
        </xsl:if>
    </xsl:template>

    <xsl:template name="parent_info">
        <xsl:param name="parent" />
        <xsl:for-each select="$parent/p:datafield[@tag='003@']/p:subfield[@code='0']"> <!-- 0100 PPN -->
            <mods:identifier type="uri">
                <xsl:value-of select="concat('https://uri.gbv.de/document/',$MCR.PICA2MODS.DATABASE,':ppn:', .)" />
            </mods:identifier>
        </xsl:for-each>
        <xsl:for-each select="$parent/p:datafield[@tag='021A']">
            <xsl:call-template name="simple_title">
                <xsl:with-param name="datafield" select="." />
            </xsl:call-template>
        </xsl:for-each>
        <xsl:for-each select="$parent/p:datafield[@tag='006Z']/p:subfield[@code='0']">
            <mods:identifier type="zdb">
                <xsl:value-of select="." />
            </mods:identifier>
        </xsl:for-each>
    </xsl:template>
</xsl:stylesheet>
