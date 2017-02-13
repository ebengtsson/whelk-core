package whelk

import org.codehaus.jackson.map.ObjectMapper

/**
 * Used to generate a Libris XL index config (index mappings) for ElasticSearch, based on a template
 * (an existing config) and the "display-information" from the viewer.
 */
class ElasticConfigGenerator {

    private final static mapper = new ObjectMapper()
    private final static int cardBoostBase = 500 // Arbitrary number, intended to set card boosts higher than all other boosts.

    /**
     * Generates an ES config (mapping).
     * @param templateString The json TEXT contents of the template
     * @param displayInfoString The json TEXT contents of the display info
     */
    public static String generate(String templateString, String displayInfoString) {

        LinkedHashSet orderedCardProperties = parseCardProperties(displayInfoString)
        Map propertyValues = generatePropertyValues(orderedCardProperties)

        Map templateMap = mapper.readValue(templateString, Map)

        Map categories = templateMap["mappings"]
        for (category in categories) {

            // Ignore everything except auth, bib and hold
            String key = category.getKey()
            if (key != "auth" && key != "bib" && key != "hold")
                continue

            Map properties = category.getValue()["properties"]["about"]["properties"]
            for (property in properties) {
                if (property.getKey() in orderedCardProperties) {
                    boostProperty( property, propertyValues )
                }
            }
        }

        return mapper.writeValueAsString(templateMap)
    }

    /**
     * Generate a Map, from property names, to the values with which they should be boosted.
     * @param orderedCardProperties The ordered set of card properties. item N should have priority over item N+1.
     * @return A map where each property name is mapped to an integer boost value for said property.
     */
    private static Map generatePropertyValues(LinkedHashSet orderedCardProperties) {
        int boost = cardBoostBase + orderedCardProperties.size()
        Map propertyValues = [:]

        for (property in orderedCardProperties) {
            propertyValues[property] = boost--
        }
        return propertyValues
    }

    /**
     * Add a boosting offset to the given property
     * @param property  The entry in the templateMap (json structure) representing the base of a given property,
     *                  For example:
     *                      "familyName": {
     *                          "analyzer": "completer",
     *                          "type": "string"
     *                      },
     */
    private static void boostProperty(property, propertyValues) {
        Map propertyMap = property.getValue()
        def existingBoost = propertyMap["boost"]
        Integer boost = propertyValues[property.getKey()]
        if (existingBoost)
            boost += existingBoost
        propertyMap["boost"] = boost
    }

    /**
     * Parse the display info and extract a set of all properties that are "card properties".
     * @param displayInfoString The json TEXT contents of the display info
     * @return Ordered set of card properties
     */
    private static LinkedHashSet parseCardProperties(String displayInfoString) {
        Map displayMap = mapper.readValue(displayInfoString, Map)
        Set cardProperties = new LinkedHashSet() // A LinkedHashSet is used instead of a set, to preserve item order.

        Map categories = displayMap["lensGroups"]["cards"]["lenses"]
        for (category in categories) {
            Map categoryBody = category.getValue()
            for (property in categoryBody["showProperties"]){
                cardProperties.add( property.toString() )
            }
        }

        return cardProperties
    }
}