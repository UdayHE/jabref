package org.jabref.logic.importer.fetcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jabref.logic.cleanup.FieldFormatterCleanup;
import org.jabref.logic.formatter.bibtexfields.ClearFormatter;
import org.jabref.logic.formatter.bibtexfields.NormalizeMonthFormatter;
import org.jabref.logic.formatter.bibtexfields.NormalizeNamesFormatter;
import org.jabref.logic.help.HelpFile;
import org.jabref.logic.importer.FetcherException;
import org.jabref.logic.importer.IdBasedParserFetcher;
import org.jabref.logic.importer.Parser;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.importer.SearchBasedFetcher;
import org.jabref.logic.importer.fetcher.transformers.MedlineQueryTransformer;
import org.jabref.logic.importer.fileformat.MedlineImporter;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.field.UnknownField;

import org.apache.hc.core5.net.URIBuilder;
import org.apache.lucene.queryparser.flexible.core.nodes.QueryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetch or search from PubMed <a href="http://www.ncbi.nlm.nih.gov/sites/entrez/">www.ncbi.nlm.nih.gov</a>
 * The MedlineFetcher fetches the entries from the PubMed database.
 * See <a href="https://docs.jabref.org/collect/import-using-online-bibliographic-database#medline-pubmed">docs.jabref.org</a> for a detailed documentation of the available fields.
 */
public class MedlineFetcher implements IdBasedParserFetcher, SearchBasedFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MedlineFetcher.class);

    private static final int NUMBER_TO_FETCH = 50;
    private static final String ID_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    private static final String SEARCH_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";

    private int numberOfResultsFound;

    /**
     * When using 'esearch.fcgi?db=&lt;database>&term=&lt;query>' we will get a list of IDs matching the query.
     * Input: Any text query (&term)
     * Output: List of UIDs matching the query
     *
     * @see <a href="https://www.ncbi.nlm.nih.gov/books/NBK25500/">www.ncbi.nlm.nih.gov/books/NBK25500/</a>
     */
    private List<String> getPubMedIdsFromQuery(String query) throws FetcherException {
        boolean fetchIDs = false;
        boolean firstOccurrenceOfCount = false;
        List<String> idList = new ArrayList<>();
        try {
            URL ncbi = createSearchUrl(query);

            XMLInputFactory inputFactory = XMLInputFactory.newFactory();
            XMLStreamReader streamReader = inputFactory.createXMLStreamReader(ncbi.openStream());

            fetchLoop:
            while (streamReader.hasNext()) {
                int event = streamReader.getEventType();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        if ("Count".equals(streamReader.getName().toString())) {
                            firstOccurrenceOfCount = true;
                        }

                        if ("IdList".equals(streamReader.getName().toString())) {
                            fetchIDs = true;
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        if (firstOccurrenceOfCount) {
                            numberOfResultsFound = Integer.parseInt(streamReader.getText());
                            firstOccurrenceOfCount = false;
                        }

                        if (fetchIDs) {
                            idList.add(streamReader.getText());
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        // Everything relevant is listed before the IdList. So we break the loop right after the IdList tag closes.
                        if ("IdList".equals(streamReader.getName().toString())) {
                            break fetchLoop;
                        }
                }
                streamReader.next();
            }
            streamReader.close();
            return idList;
        } catch (IOException | URISyntaxException e) {
            throw new FetcherException("Unable to get PubMed IDs", Localization.lang("Unable to get PubMed IDs"), e);
        } catch (XMLStreamException e) {
            throw new FetcherException("Error while parsing ID list", Localization.lang("Error while parsing ID list"),
                    e);
        }
    }

    @Override
    public String getName() {
        return "Medline/PubMed";
    }

    @Override
    public Optional<HelpFile> getHelpPage() {
        return Optional.of(HelpFile.FETCHER_MEDLINE);
    }

    @Override
    public URL getUrlForIdentifier(String identifier) throws URISyntaxException, MalformedURLException {
        URIBuilder uriBuilder = new URIBuilder(ID_URL);
        uriBuilder.addParameter("db", "pubmed");
        uriBuilder.addParameter("retmode", "xml");
        uriBuilder.addParameter("id", identifier);
        return uriBuilder.build().toURL();
    }

    @Override
    public Parser getParser() {
        return new MedlineImporter();
    }

    @Override
    public void doPostCleanup(BibEntry entry) {
        new FieldFormatterCleanup(new UnknownField("journal-abbreviation"), new ClearFormatter()).cleanup(entry);
        new FieldFormatterCleanup(new UnknownField("status"), new ClearFormatter()).cleanup(entry);
        new FieldFormatterCleanup(new UnknownField("copyright"), new ClearFormatter()).cleanup(entry);

        new FieldFormatterCleanup(StandardField.MONTH, new NormalizeMonthFormatter()).cleanup(entry);
        new FieldFormatterCleanup(StandardField.AUTHOR, new NormalizeNamesFormatter()).cleanup(entry);
    }

    private URL createSearchUrl(String query) throws URISyntaxException, MalformedURLException {
        URIBuilder uriBuilder = new URIBuilder(SEARCH_URL);
        uriBuilder.addParameter("db", "pubmed");
        uriBuilder.addParameter("sort", "relevance");
        uriBuilder.addParameter("retmax", String.valueOf(NUMBER_TO_FETCH));
        uriBuilder.addParameter("term", query); // already lucene query
        return uriBuilder.build().toURL();
    }

    /**
     * Fetch and parse an medline item from eutils.ncbi.nlm.nih.gov.
     * The E-utilities generate a huge XML file containing all entries for the ids
     *
     * @param ids A list of IDs to search for.
     * @return Will return an empty list on error.
     */
    private List<BibEntry> fetchMedline(List<String> ids) throws FetcherException {
        try {
            // Separate the IDs with a comma to search multiple entries
            URL fetchURL = getUrlForIdentifier(String.join(",", ids));
            URLConnection data = fetchURL.openConnection();
            ParserResult result = new MedlineImporter().importDatabase(
                    new BufferedReader(new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8)));
            if (result.hasWarnings()) {
                LOGGER.warn(result.getErrorMessage());
            }
            List<BibEntry> resultList = result.getDatabase().getEntries();
            resultList.forEach(this::doPostCleanup);
            return resultList;
        } catch (URISyntaxException | MalformedURLException e) {
            throw new FetcherException("Error while generating fetch URL",
                    Localization.lang("Error while generating fetch URL"), e);
        } catch (IOException e) {
            throw new FetcherException("Error while fetching from Medline",
                    Localization.lang("Error while fetching from %0", "Medline"), e);
        }
    }

    @Override
    public List<BibEntry> performSearch(QueryNode luceneQuery) throws FetcherException {
        List<BibEntry> entryList;
        MedlineQueryTransformer transformer = new MedlineQueryTransformer();
        Optional<String> transformedQuery = transformer.transformLuceneQuery(luceneQuery);

        if (transformedQuery.isEmpty() || transformedQuery.get().isBlank()) {
            return List.of();
        } else {
            // searching for pubmed ids matching the query
            List<String> idList = getPubMedIdsFromQuery(transformedQuery.get());

            if (idList.isEmpty()) {
                LOGGER.info("No results found.");
                return List.of();
            }
            if (numberOfResultsFound > NUMBER_TO_FETCH) {
                LOGGER.info("{} results found. Only 50 relevant results will be fetched by default.", numberOfResultsFound);
            }

            // pass the list of ids to fetchMedline to download them. like a id fetcher for mutliple ids
            entryList = fetchMedline(idList);

            return entryList;
        }
    }
}
