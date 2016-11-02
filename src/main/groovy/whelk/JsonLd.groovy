package whelk

import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import se.kb.libris.util.marc.Controlfield
import se.kb.libris.util.marc.Datafield
import whelk.converter.marc.JsonLD2MarcXMLConverter
import whelk.exception.FramingException
import whelk.exception.ModelValidationException

import se.kb.libris.util.marc.io.MarcXmlRecordReader
import se.kb.libris.util.marc.MarcRecord
import whelk.util.PropertyLoader

public class JsonLd {

    static final String GRAPH_KEY = "@graph"
    static final String ID_KEY = "@id"
    static final String THING_KEY = "mainEntity"
    static final String RECORD_KEY = "meta"
    static final String TYPE_KEY = "@type"
    static final String CREATED_KEY = "created"
    static final String MODIFIED_KEY = "modified"
    static final String DELETED_KEY = "deleted"
    static final String COLLECTION_KEY = "collection"
    static final String CONTENT_TYPE_KEY = "contentType"
    static final String CHECKSUM_KEY = "checksum"
    static final String NON_JSON_CONTENT_KEY = "content"
    static final String ALTERNATE_ID_KEY = "identifiers"
    static final String JSONLD_ALT_ID_KEY = "sameAs"
    static final String CONTROL_NUMBER_KEY = "controlNumber"
    static final String ABOUT_KEY = "mainEntity"
    static final String APIX_FAILURE_KEY = "apixExportFailedAt"
    static final String ENCODING_LEVEL_KEY = "marc:encLevel"
    static final String HOLDING_FOR_KEY = "holdingFor"

    static final ObjectMapper mapper = new ObjectMapper()
    static final JsonLD2MarcXMLConverter converter = new JsonLD2MarcXMLConverter()

    private static Logger log = LoggerFactory.getLogger(JsonLd.class)

    /**
     * This flatten-method does not create description-based flat json
     * (i.e. with entry, items and quoted)
     *
     */
    static Map flatten(Map framedJsonLd) {
        if (isFlat(framedJsonLd) || !framedJsonLd.containsKey(ID_KEY)) {
            return framedJsonLd
        }

        def flatList = []

        storeFlattened(framedJsonLd, flatList)

        return [(GRAPH_KEY): flatList.reverse()]
    }

    private static storeFlattened(current, result) {
        if (current instanceof Map) {
            def flattened = makeFlat(current, result)
            if (flattened.containsKey(ID_KEY) && flattened.size() > 1) {
                if (!result.contains(flattened)) {
                    result.add(flattened)
                }
            }
            def itemid = current.get(ID_KEY)
            return (itemid ? [(ID_KEY): itemid] : flattened)
        }
        return current
    }

    private static makeFlat(obj, result) {
        def updated = [:]
        obj.each { key, value ->
            if (value instanceof List) {
                def newvaluelist = []
                for (o in value) {
                    newvaluelist.add(storeFlattened(o, result))
                }
                value = newvaluelist
            } else {
                value = storeFlattened(value, result)
            }
            updated[(key)] = value
        }
        return updated
    }

    public static Map frameAndExpand(String mainId, Map jsonLd,
                                     boolean includeQuoted=true) {
        try {
            Map objectsWithId = getObjectsWithId(jsonLd, includeQuoted)
            Map expanded = expandMainItem(mainId, objectsWithId)

            Set referencedBNodes = new HashSet()
            getReferencedBNodes(expanded, referencedBNodes)

            cleanUnreferencedBNodeIDs(expanded, referencedBNodes)

            return expanded
        } catch (StackOverflowError soe) {
            throw new FramingException("Circular dependency in input")
        }
    }

    public static Map getObjectsWithId(Map jsonLd,
                                       boolean includeQuoted=true) {
        // expected input: {"@graph": [{"@id": "/foo", ...}, {"@id": ...}]}
        List items
        if (jsonLd.containsKey(GRAPH_KEY)) {
            items = jsonLd.get(GRAPH_KEY)
        } else {
            throw new FramingException("Missing '@graph' key in input")
        }

        Map objectsWithId = [:]

        items.each { item ->
            if (item instanceof Map && item.containsKey(GRAPH_KEY)) {
                if (includeQuoted) {
                    item = item[GRAPH_KEY]
                } else {
                    // groovy doesn't allow continue in closures :(
                    return
                }
            }

            if (item instanceof Map && item.containsKey(ID_KEY)) {
                String id = item.get(ID_KEY)
                objectsWithId[id] = item
            }
        }

        return objectsWithId
    }

    public static Map expandMainItem(String id, Map objectsWithId) {
        if (objectsWithId.containsKey(id)) {
            Map mainItem = objectsWithId[id]
            expandItem(mainItem, objectsWithId)
        } else {
            throw new FramingException("Could not find main item with ID ${id}")
        }
    }

    public static Map expandItem(Map item, Map objectsWithId) {
        Tuple2 acc0 = new Tuple2([:], [:])
        Tuple2 result = item.inject(acc0) { acc, key, value ->
            def newMainItem = acc.first
            def expandedObjects = acc.second

            Tuple2 expanded = expand(value, objectsWithId, expandedObjects)

            def newValue = expanded.first
            def newExpandedObjects = expanded.second

            newMainItem[key] = newValue
            new Tuple2(newMainItem, newExpandedObjects)
        }

        return result.first
    }

    public static Tuple2 expand(Object object, Map objectsWithId,
                                Map expandedObjects) {
        if (object instanceof List) {
            return expandList(object, objectsWithId, expandedObjects)
        } else if (object instanceof Map) {
            return expandMap(object, objectsWithId, expandedObjects)
        } else {
            return new Tuple2(object, expandedObjects)
        }
    }

    public static Tuple2 expandList(List list, Map objectsWithId,
                                    Map alreadyExpandedObjects) {
      Tuple2 acc0 = new Tuple2([], alreadyExpandedObjects)
      list.inject(acc0) { acc, item ->
        List result = acc.first
        Map expandedObjects = acc.second
        Tuple2 expanded = expand(item, objectsWithId, expandedObjects)
        def expandedItem = expanded.first
        def newExpandedObjects = expanded.second
        result << expandedItem
        return new Tuple2(result, newExpandedObjects)
      }
    }

    public static Tuple2 expandMap(Map input, Map objectsWithId,
                                   Map expandedObjects) {
        def id = input.get(ID_KEY)
        if (id && expandedObjects.containsKey(id)) {
            // we've already expanded this, so we just return it
            Tuple2 result = new Tuple2(expandedObjects[id], expandedObjects)
            return result
        } else if (id && objectsWithId.containsKey(id)) {
            // perform local expansion
            Map expanded = expandItem(objectsWithId[id], objectsWithId)
            id = expanded.get(ID_KEY)
            expandedObjects[id] = expanded
            return new Tuple2(expanded, expandedObjects)
        } else {
            Tuple2 acc0 = new Tuple2([:], expandedObjects)
            return input.inject(acc0) { acc, key, value ->
                Map result = acc.first
                Map expandedObjects0 = acc.second
                Tuple2 expanded = expand(value, objectsWithId, expandedObjects0)
                Object newValue = expanded.first
                Map newExpandedObjects = expanded.second
                result[key] = newValue
                return new Tuple2(result, newExpandedObjects)
            }
        }
    }

    public static Map frame(String mainId, Map flatJsonLd) {
        return frame(mainId, null, flatJsonLd)
    }

    public static Map frame(String mainId, String thingLink, Map flatJsonLd) {
        if (isFramed(flatJsonLd)) {
            return flatJsonLd
        }

        Map flatCopy = (Map) Document.deepCopy(flatJsonLd)

        if (mainId) {
            mainId = Document.BASE_URI.resolve(mainId)
        }

        def idMap = getIdMap(flatCopy)

        def mainItem = idMap[mainId]
        if (mainItem) {
            if (thingLink) {
                def thingRef = mainItem[thingLink]
                if (thingRef) {
                    def thingId = thingRef[ID_KEY]
                    def thing = idMap[thingId]
                    thing[RECORD_KEY] = [(ID_KEY): mainId]
                    mainId = thingId
                    idMap[mainId] = thingRef
                    mainItem = thing
                    log.debug("Using think-link. Framing around ${mainId}")
                }
            }
        } else {
            log.debug("No main item map found for $mainId, trying to find an identifier")
            // Try to find an identifier to frame around
            String foundIdentifier = Document.BASE_URI.resolve(findIdentifier(flatCopy))

            log.debug("Result of findIdentifier: $foundIdentifier")
            if (foundIdentifier) {
                mainItem = idMap.get(foundIdentifier)
            }
        }
        Map framedMap
        try {
            framedMap = embed(mainId, mainItem, idMap, new HashSet<String>())
            if (!framedMap) {
                throw new FramingException("Failed to frame JSONLD ($flatJsonLd)")
            }
        } catch (StackOverflowError sofe) {
            throw new FramingException("Unable to frame JSONLD ($flatJsonLd). Recursive loop?)", sofe)
        }

        Set referencedBNodes = new HashSet()
        getReferencedBNodes(framedMap, referencedBNodes)

        cleanUnreferencedBNodeIDs(framedMap, referencedBNodes)

        return framedMap
    }

    /**
     * Fills the referencedBNodes set with all "_:*" ids that are referenced anywhere in the structure/document
     * (and thus cannot be safely removed)
     */
    public static void getReferencedBNodes(Map map, Set referencedBNodes) {
        // A jsonld reference is denoted as a json object containing exactly one member, with the key "@id".
        if (map.size() == 1) {
            String key = map.keySet().getAt(0)
            if (key.equals("@id")) {
                String id = map.get(key)
                if (id.startsWith("_:"))
                    referencedBNodes.add(id)
            }
        }

        for (Object keyObj : map.keySet()) {
            Object subobject = map.get(keyObj)

            if (subobject instanceof Map)
                getReferencedBNodes((Map) subobject, referencedBNodes)
            else if (subobject instanceof List)
                getReferencedBNodes((List) subobject, referencedBNodes)
        }
    }

    public static void getReferencedBNodes(List list, Set referencedBNodes) {
        for (Object item : list) {
            if (item instanceof Map)
                getReferencedBNodes((Map) item, referencedBNodes)
        }
    }

    public static void cleanUnreferencedBNodeIDs(Map map, Set referencedBNodes) {
        if (map.size() > 1) {
            if (map.containsKey("@id")) {
                String id = map.get("@id")

                if (id.startsWith("_:") && !referencedBNodes.contains(id)) {
                    map.remove("@id")
                }
            }
        }

        for (Object keyObj : map.keySet()) {
            Object subobject = map.get(keyObj)

            if (subobject instanceof Map)
                cleanUnreferencedBNodeIDs((Map) subobject, referencedBNodes)
            else if (subobject instanceof List)
                cleanUnreferencedBNodeIDs((List) subobject, referencedBNodes)
        }
    }

    public static void cleanUnreferencedBNodeIDs(List list, Set referencedBNodes) {
        for (Object item : list) {
            if (item instanceof Map)
                cleanUnreferencedBNodeIDs((Map) item, referencedBNodes)
        }
    }

    private static Map embed(String mainId, Map mainItemMap, Map idMap, Set embedChain) {
        embedChain.add(mainId)
        mainItemMap.each { key, value ->
            mainItemMap.put(key, toEmbedded(value, idMap, embedChain))
        }
        return mainItemMap
    }

    private static Object toEmbedded(Object o, Map idMap, Set embedChain) {
        if (o instanceof List) {
            def newList = []
            o.each {
                newList.add(toEmbedded(it, idMap, embedChain))
            }
            return newList
        }
        if (o instanceof Map) {
            def obj = null
            def oId = o.get(ID_KEY)
            if (!oId) {
                obj = o
            } else if (!embedChain.contains(oId)) {
                obj = idMap.get(oId)
            }
            if (obj) {
                return embed(oId, obj, idMap, embedChain)
            }
        }
        return o
    }

    static URI findRecordURI(Map jsonLd) {
        String foundIdentifier = findIdentifier(jsonLd)
        if (foundIdentifier) {
            return Document.BASE_URI.resolve(foundIdentifier)
        }
        return null
    }

    static String findIdentifier(Map jsonLd) {
        String foundIdentifier = null
        if (!jsonLd) {
            return null
        }
        if (isFlat(jsonLd)) {
            log.trace("Received json is flat")
            if (jsonLd.containsKey(GRAPH_KEY)) {
                foundIdentifier = jsonLd.get(GRAPH_KEY).first().get(ID_KEY)
            }
        }
        if (isFramed(jsonLd)) {
            foundIdentifier = jsonLd.get(ID_KEY)
        }
        if (foundIdentifier) {
            if (foundIdentifier.startsWith("/") || foundIdentifier.startsWith(Document.BASE_URI.toString())) {
                // Assumes only identifier in uri path
                return Document.BASE_URI.resolve(foundIdentifier).getPath().substring(1)
            }
            return foundIdentifier
        }
        return null
    }



    static boolean isFlat(Map jsonLd) {
        if ((jsonLd.containsKey(GRAPH_KEY) && jsonLd.get(GRAPH_KEY) instanceof List)) {
            return true
        }
        return false
    }

    static boolean isFramed(Map jsonLd) {
        if (jsonLd && !jsonLd.containsKey(GRAPH_KEY)) {
            return true
        }
        return false
    }

    private static Map getIdMap(Map flatJsonLd) {
        Map idMap = [:]
        if (flatJsonLd.containsKey(GRAPH_KEY)) {
            for (item in flatJsonLd.get(GRAPH_KEY)) {
                if (item.containsKey(GRAPH_KEY)) {
                    item = item.get(GRAPH_KEY)
                }
                if (item.containsKey(ID_KEY)) {
                    def id = item.get(ID_KEY)
                    if (idMap.containsKey(id)) {
                        Map existing = idMap.get(id)
                        idMap.put(id, existing + item)
                    } else {
                        idMap.put(id, item)
                    }
                }
            }
        }
        return idMap
    }

    static boolean validateItemModel(Document doc) {
        if (!doc || !doc.data) {
            throw new ModelValidationException("Document has no data to validate.")
        }

        // The real test of the "Item Model" is whether or not the supplied
        // document can be converted into some kind of correct(ish) MARC.

        MarcRecord marcRecord
        try {
            Document convertedDocument = converter.convert(doc.data, doc.id)
            String convertedText = (String) convertedDocument.data.get("content")
            marcRecord = MarcXmlRecordReader.fromXml(convertedText)
        } catch (Throwable e) {
            // Catch _everything_ that could go wrong with the convert() call,
            // including Asserts (Errors)
            return false
        }

        // Do some basic sanity checking on the resulting MARC holdings post.

        // Holdings posts must have 32 positions in 008
        for (Controlfield field008 : marcRecord.getControlfields("008")) {
            if (field008.getData().length() != 32) {
                return false
            }
        }

        // Holdings posts must have (at least one) 852 $b (sigel)
        boolean containsSigel = false
        for (Datafield field852 : marcRecord.getDatafields("852")) {
            if (field852.getSubfields("b").size() > 0) {
                containsSigel = true
                break
            }
        }
        if (!containsSigel) {
            return false
        }

        return true
    }
}
