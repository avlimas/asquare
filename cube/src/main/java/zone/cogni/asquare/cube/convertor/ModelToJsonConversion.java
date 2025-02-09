package zone.cogni.asquare.cube.convertor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Preconditions;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zone.cogni.asquare.applicationprofile.model.basic.ApplicationProfile;
import zone.cogni.asquare.cube.convertor.ModelToJsonConversion.Configuration.JsonRootType;
import zone.cogni.asquare.cube.convertor.json.ApplicationProfileToConversionProfile;
import zone.cogni.asquare.cube.convertor.json.ConversionProfile;
import zone.cogni.libs.jena.utils.JenaUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static zone.cogni.asquare.cube.convertor.ModelToJsonConversion.Configuration.JsonType;
import static zone.cogni.asquare.cube.convertor.ModelToJsonConversion.Configuration.ModelType;

public class ModelToJsonConversion implements BiFunction<Model, String, ObjectNode> {

  private static final Logger log = LoggerFactory.getLogger(ModelToJsonConversion.class);

  public static class Configuration {
    /**
     * JSON contains a field "rootType" which sets the main type like "Dog" without its superclasses.
     */
    public enum JsonRootType {ENABLED, DISABLED}

    /**
     * JSON contains a field "type" which can contain all types like "Dog", "Mammal" and "Animal".
     * But it can also contain only the root type "Dog" or nothing (if JsonRootType is enabled).
     */
    public enum JsonType {ALL, ROOT, DISABLED}

    /**
     * Model typically contains all types, like demo:Dog, demo:Mammal and demo:Animal,
     * or a subset, like demo:Dog and demo:Animal,
     * or the root type only like demo:Dog.
     */
    public enum ModelType {ALL, PROFILE, ROOT}


    private boolean logIssues;
    private Set<String> ignoredProperties = new HashSet<>();

    private JsonRootType jsonRootType = JsonRootType.DISABLED;
    private JsonType jsonType = JsonType.ALL;
    private ModelType modelType = ModelType.ALL;

    private boolean inverseAttributesSupported;

    public boolean isLogIssues() {
      return logIssues;
    }

    public void setLogIssues(boolean logIssues) {
      this.logIssues = logIssues;
    }

    public Set<String> getIgnoredProperties() {
      return ignoredProperties;
    }

    public void setIgnoredProperties(Set<String> ignoredProperties) {
      this.ignoredProperties = ignoredProperties;
    }

    public boolean isIgnoredProperty(String property) {
      return this.ignoredProperties.contains(property);
    }

    public JsonRootType getJsonRootType() {
      return jsonRootType;
    }

    public boolean isJsonRootType(JsonRootType jsonRootType) {
      return this.jsonRootType == jsonRootType;
    }

    public void setJsonRootType(JsonRootType jsonRootType) {
      this.jsonRootType = jsonRootType;
    }

    public JsonType getJsonType() {
      return jsonType;
    }

    public boolean isJsonType(JsonType jsonType) {
      return this.jsonType == jsonType;
    }

    public void setJsonType(JsonType jsonType) {
      this.jsonType = jsonType;
    }

    public ModelType getModelType() {
      return modelType;
    }

    public boolean isModelType(ModelType modelType) {
      return this.modelType == modelType;
    }

    public void setModelType(ModelType modelType) {
      this.modelType = modelType;
    }

    public boolean isInverseAttributesSupported() {
      return inverseAttributesSupported;
    }

    public void setInverseAttributesSupported(boolean inverseAttributesSupported) {
      this.inverseAttributesSupported = inverseAttributesSupported;
    }

    public void check() {
      if (jsonRootType == null || jsonType == null || modelType == null)
        throw new RuntimeException("Please configure all of 'jsonRootType', 'jsonType' and 'modelType'.");

      if (jsonRootType == JsonRootType.DISABLED && jsonType == JsonType.DISABLED)
        throw new RuntimeException("Please enable at least one of 'jsonRootType' or 'jsonType'.");
    }
  }

  private final Configuration configuration;
  private final ConversionProfile conversionProfile;

  public ModelToJsonConversion(Configuration configuration, ConversionProfile conversionProfile) {
    this.configuration = configuration;
    this.conversionProfile = conversionProfile;

    configuration.check();
  }

  public ModelToJsonConversion(Configuration configuration, ApplicationProfile applicationProfile) {
    this(configuration, new ApplicationProfileToConversionProfile().apply(applicationProfile));
  }

  @Override
  public ObjectNode apply(Model model, String root) {
    Context context = new Context(this, model);
    Resource subject = ResourceFactory.createResource(root);

    ObjectNode data = context.jsonRoot.putObject("data");
    processInstance(context, subject, data);

    if (configuration.logIssues) {
      reportMissedSubjects(context);
      reportUnprocessedTriples(context);
    }

    return context.jsonRoot;
  }

  private void reportMissedSubjects(Context context) {
    Set<Resource> missedSubjects = context.subjectTypeMap.keySet();
    missedSubjects.removeAll(context.alreadyProcessedResources);

    if (missedSubjects.size() > 1) {
      log.warn("missed {} subjects out of {}. missed subjects: {}",
               missedSubjects.size(),
               context.subjectTypeMap.size(),
               missedSubjects);
    }
  }

  private void reportUnprocessedTriples(Context context) {
    if (!log.isWarnEnabled()) return;

    Model remainingModel = ModelFactory.createDefaultModel();
    remainingModel.add(context.model);
    remainingModel.remove(context.alreadyProcessedModel);

    if (remainingModel.size() > 1) {
      log.warn("missed {} triples \n{}",
               remainingModel.size(),
               JenaUtils.toString(remainingModel, "ttl"));
    }
  }

  /**
   * Processes a single subject with all its properties and values.
   *
   * @param context      of processing
   * @param subject      currently being added in JSON
   * @param instanceRoot current root where JSON is going to be manipulated
   */
  private void processInstance(Context context,
                                  Resource subject,
                                  ObjectNode instanceRoot) {
    // only process once, at most
    if (context.alreadyProcessedResources.contains(subject)) return;

    // process instance fields
    ConversionProfile.Type type = context.subjectTypeMap.get(subject);
    setInstanceUri(subject, instanceRoot);
    setInstanceType(instanceRoot, type);
    setInstanceRootType(instanceRoot, type);

    // bookkeeping -> must be before processing attributes !
    context.alreadyProcessedResources.add(subject);
    getTypeStatements(subject, type)
            .forEach(context.alreadyProcessedModel::add);

    // process attributes
    type.getAttributes().forEach(attribute -> {
      processAttribute(context, subject, type, instanceRoot, attribute);
    });
  }

  private Stream<Statement> getTypeStatements(Resource subject, ConversionProfile.Type type) {
    return type.getRdfTypes()
               .stream()
               .map(ResourceFactory::createResource)
               .map(typeResource -> ResourceFactory.createStatement(subject, RDF.type, typeResource));
  }

  /**
   * Process a single attribute of a subject with all its values.
   *
   * @param context      of processing
   * @param subject      currently being added in JSON
   * @param type         of subject
   * @param instanceRoot current root where JSON is going to be manipulated
   * @param attribute    currently being added in JSON
   */
  private void processAttribute(Context context,
                                Resource subject,
                                ConversionProfile.Type type,
                                ObjectNode instanceRoot,
                                ConversionProfile.Attribute attribute) {
    // if no values then return
    List<RDFNode> values = getValues(context, subject, attribute);
    if (values.isEmpty()) return;

    // log issue if we find inverses and inverse support is disabled!
    if (attribute.isInverse() && !configuration.inverseAttributesSupported) {
      String valuesAsString = values.stream().map(RDFNode::toString).collect(Collectors.joining(", "));
      log.error("inverse properties disabled and uri '{}' has inverse attribute '{}' with values: {}",
                subject.getURI(), attribute.getAttributeId(), valuesAsString);
      return;
    }

    // add attributes values to JSON
    setJsonAttribute(instanceRoot, type, attribute, values);

    // add includes to JSON (here or in setJsonAttribute?)
    if (attribute.isReference()) {
      values.forEach(value -> {
        createAndIncludeInstance(context, type, attribute, value);
      });
    }
  }

  /**
   * Returns list of <code>RDFNode</code> which are values of <code>subject</code> and <code>attribute</code>.
   * Takes into account whether attribute is <code>inverse</code> or not.
   *
   * @param context   of processing
   * @param subject   currently being added in JSON
   * @param attribute currently being added in JSON
   * @return list of <code>RDFNode</code> which are values of <code>subject</code> and <code>attribute</code>
   */
  private List<RDFNode> getValues(Context context,
                                  Resource subject,
                                  ConversionProfile.Attribute attribute) {
    StmtIterator iterator = context.model.listStatements(attribute.isInverse() ? null : subject,
                                                         attribute.getProperty(),
                                                         attribute.isInverse() ? subject : null);

    List<RDFNode> result = new ArrayList<>();
    while (iterator.hasNext()) {
      Statement statement = iterator.nextStatement();

      context.alreadyProcessedModel.add(statement);
      result.add(attribute.isInverse() ? statement.getSubject() : statement.getObject());
    }

    return result;
  }

  /**
   * Adds values to <code>instanceRoot</code> JSON.
   *
   * @param instanceRoot current root where JSON is going to be manipulated
   * @param type         of subject
   * @param attribute    currently being added in JSON
   * @param values       to add to <code>instanceRoot</code>
   */
  private void setJsonAttribute(ObjectNode instanceRoot,
                                ConversionProfile.Type type,
                                ConversionProfile.Attribute attribute,
                                List<RDFNode> values) {
    if (attribute.isReference()) {
      addReferences(instanceRoot, attribute, values);
      return;
    }
    else if (attribute.isAttribute()) {
      addAttributes(instanceRoot, attribute, values);
      return;
    }

    throw new RuntimeException("should not be able to get here:" +
                               " type " + type.getRootClassId() + " and property " + attribute.getAttributeId());
  }

  /**
   * Adds references to <code>instanceRoot</code> JSON.
   *
   * @param instanceRoot current root where JSON is going to be manipulated
   * @param attribute    currently being added in JSON
   * @param values       to add to <code>instanceRoot</code>
   */
  private void addReferences(ObjectNode instanceRoot,
                             ConversionProfile.Attribute attribute,
                             List<RDFNode> values) {
    ObjectNode referencesNode = getOrCreateObjectNode(instanceRoot, "references");

    if (attribute.isList()) {
      // list case
      ArrayNode arrayNode = instanceRoot.arrayNode();
      referencesNode.set(attribute.getAttributeId(), arrayNode);

      values.forEach(v -> arrayNode.add(referencesNode.textNode(v.asResource().getURI())));
    }
    else {
      // single case
      if (values.size() != 1) {
        throw new RuntimeException("attribute " + attribute.getAttributeId() + " has values " + values);
      }

      TextNode singleReference = referencesNode.textNode(values.get(0).asResource().getURI());

      referencesNode.set(attribute.getAttributeId(), singleReference);
    }
  }

  /**
   * Adds attributes to <code>instanceRoot</code> JSON.
   *
   * @param instanceRoot current root where JSON is going to be manipulated
   * @param attribute    currently being added in JSON
   * @param values       to add to <code>instanceRoot</code>
   */
  private void addAttributes(ObjectNode instanceRoot,
                             ConversionProfile.Attribute attribute,
                             List<RDFNode> values) {
    if (values.isEmpty()) return;

    ObjectNode attributesNode = getOrCreateObjectNode(instanceRoot, "attributes");

    // single but with multiple languages
    if (attribute.isSingle() && values.size() > 1) {
      ObjectNode attributeNode = getOrCreateObjectNode(attributesNode, attribute.getAttributeId());

      Set<String> languages = new HashSet<>();
      // assume language nodes!
      values.forEach(languageRdfNode -> {
        Preconditions.checkState(languageRdfNode.isLiteral(), "Node is not a literal: " + attribute.getAttributeId());
        Preconditions.checkState(languageRdfNode.asLiteral().getDatatype()
                                                .equals(RDFLangString.rdfLangString), "Node is not a lang literal: " + attribute.getAttributeId());

        // check for duplicates !
        String language = languageRdfNode.asLiteral().getLanguage();
        Preconditions.checkState(!languages.contains(language), "More than 1 lang literals for the same language: " + attribute.getAttributeId());

        languages.add(language);

        String text = languageRdfNode.asLiteral().getString();
        ObjectNode languageNode = getOrCreateObjectNode(attributeNode, "rdf:langString");
        addToJsonAsSingle(languageNode, language, languageNode.textNode(text));
      });
      return;
    }

    // single
    if (attribute.isSingle()) {
      if (values.size() != 1) {
        throw new RuntimeException("attribute " + attribute.getAttributeId() + " has " + values
                .size() + " values: " + values);
      }

      RDFNode rdfNode = values.get(0);
      ObjectNode attributeNode = getOrCreateObjectNode(attributesNode, attribute.getAttributeId());

      if (rdfNode.isAnon()) throw new RuntimeException("blank nodes are not supported");

      if (rdfNode.isURIResource()) {
        attributeNode.set("rdfs:Resource", attributeNode.textNode(rdfNode.asResource().getURI()));
        return;
      }

      // literal
      Literal literal = rdfNode.asLiteral();
      RDFDatatype datatype = literal.getDatatype();

      if (RDFLangString.rdfLangString.equals(datatype)) {
        String language = literal.getLanguage();
        ObjectNode languageNode = getOrCreateObjectNode(attributeNode, "rdf:langString");
        addToJsonAsSingle(languageNode, language, languageNode.textNode(literal.getString()));
        return;
      }
      if (XSDDatatype.XSDstring.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:string", getTextNode(literal.getString()));
        return;
      }
      if (XSDDatatype.XSDboolean.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:boolean", getBooleanNode(literal.getBoolean()));
        return;
      }
      if (XSDDatatype.XSDdate.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:date", getTextNode(literalToDate(literal)));
        return;
      }
      if (XSDDatatype.XSDdateTime.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:dateTime", getTextNode(literalToDateTime(literal)));
        return;
      }
      if (XSDDatatype.XSDint.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:int", getNumberNode(literal.getInt()));
        return;
      }
      if (XSDDatatype.XSDlong.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:long", getNumberNode(literal.getLong()));
        return;
      }
      if (XSDDatatype.XSDfloat.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:float", getNumberNode(literal.getFloat()));
        return;
      }
      if (XSDDatatype.XSDdouble.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:double", getNumberNode(literal.getDouble()));
        return;
      }
      if (XSDDatatype.XSDanyURI.equals(datatype)) {
        addToJsonAsSingle(attributeNode, "xsd:anyURI", getTextNode(literal.getLexicalForm()));
        return;
      }
      if (datatype != null) {
        addToJsonAsSingle(attributeNode, datatype.getURI(), getTextNode(literal.getLexicalForm()));
        return;
      }

      throw new RuntimeException("datatype not found");
    }

    // list
    values.forEach(rdfNode -> {
      if (rdfNode.isAnon()) throw new RuntimeException("blank nodes are not supported");

      String attributeId = attribute.getAttributeId();
      ObjectNode attributeNode = getOrCreateObjectNode(attributesNode, attributeId);

      if (rdfNode.isURIResource()) {
        addToArrayNode(attributeNode, "rdfs:Resource", getTextNode(rdfNode.asResource().getURI()));
        return;
      }

      // literal
      Literal literal = rdfNode.asLiteral();
      RDFDatatype datatype = literal.getDatatype();

      if (RDFLangString.rdfLangString.equals(datatype)) {
        ObjectNode langStringNode = getOrCreateObjectNode(attributeNode, "rdf:langString");

        String language = literal.getLanguage();
        addToArrayNode(langStringNode, language, getTextNode(literal.getString()));
        return;
      }
      if (XSDDatatype.XSDstring.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:string", getTextNode(literal.getString()));
        return;
      }
      if (XSDDatatype.XSDboolean.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:boolean", getBooleanNode(literal.getBoolean()));
        return;
      }
      if (XSDDatatype.XSDdate.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:date", getTextNode(literalToDate(literal)));
        return;
      }
      if (XSDDatatype.XSDdateTime.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:dateTime", getTextNode(literalToDateTime(literal)));
        return;
      }
      if (XSDDatatype.XSDint.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:int", getNumberNode(literal.getInt()));
        return;
      }
      if (XSDDatatype.XSDlong.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:long", getNumberNode(literal.getLong()));
        return;
      }
      if (XSDDatatype.XSDfloat.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:float", getNumberNode(literal.getFloat()));
        return;
      }
      if (XSDDatatype.XSDdouble.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:double", getNumberNode(literal.getDouble()));
        return;
      }
      if (XSDDatatype.XSDanyURI.equals(datatype)) {
        addToArrayNode(attributeNode, "xsd:anyURI", getTextNode(literal.getLexicalForm()));
        return;
      }
      if (datatype != null) {
        addToArrayNode(attributeNode, datatype.getURI(), getTextNode(literal.getLexicalForm()));
        return;
      }

      throw new RuntimeException("datatype not found");
    });
  }

  /**
   * Adds <code>values</code> which are typed to <code>included</code> section of JSON.
   * Recursively
   *
   * @param context of processing
   * @param value   to add to <code>included</code> JSON part
   */
  private void createAndIncludeInstance(Context context,
                                        ConversionProfile.Type type,
                                        ConversionProfile.Attribute attribute,
                                        RDFNode value) {
    if (!value.isResource()) {
      log.error("Type '{}' and attribute '{}' must contain a resource, found '{}'",
                type.getRootClassId(), attribute.getAttributeId(), value);
    }

    if (!context.subjectTypeMap.containsKey(value.asResource())) {
      log.error("Type '{}' and attribute '{}' must contain a typed resource, found a plain resource '{}'",
                type.getRootClassId(), attribute.getAttributeId(), value);
    }

    // already processed
    if (context.alreadyProcessedResources.contains(value.asResource())) return;

    // process and add as included
    ObjectNode linkedInstance = JsonNodeFactory.instance.objectNode();
    processInstance(context, value.asResource(), linkedInstance);
    addToArrayNode(context.jsonRoot, "included", linkedInstance);
  }

  private void setInstanceUri(Resource subject, ObjectNode instanceRoot) {
    instanceRoot.put("uri", subject.getURI());
  }

  private void setInstanceType(ObjectNode instanceRoot, ConversionProfile.Type type) {
    if (configuration.isJsonType(JsonType.DISABLED))
      return;

    if (configuration.isJsonType(JsonType.ROOT)) {
      instanceRoot.put("type", type.getRootClassId());
      return;
    }

    if (configuration.isJsonType(JsonType.ALL)) {
      Collection<String> classIds = type.getClassIds();
      if (classIds.size() == 1) {
        String typeValue = classIds.stream().findFirst().get();
        instanceRoot.put("type", typeValue);
      }
      else {
        ArrayNode typeArray = getOrCreateArrayNode(instanceRoot, "type");
        classIds.forEach(classId -> {
          typeArray.add(typeArray.textNode(classId));
        });
      }
      return;
    }

    throw new RuntimeException("should never get here");
  }

  private void setInstanceRootType(ObjectNode instanceRoot, ConversionProfile.Type type) {
    if (configuration.isJsonRootType(JsonRootType.DISABLED))
      return;

    if (configuration.isJsonRootType(JsonRootType.ENABLED)) {
      instanceRoot.put("rootType", type.getRootClassId());
      return;
    }

    throw new RuntimeException("should never get here");
  }

  private ObjectNode getOrCreateObjectNode(ObjectNode instanceRoot, String name) {
    if (!instanceRoot.has(name)) {
      instanceRoot.set(name, JsonNodeFactory.instance.objectNode());
    }

    return (ObjectNode) instanceRoot.get(name);
  }

  private void addToArrayNode(ObjectNode instanceRoot, String name, JsonNode value) {
    ArrayNode arrayNode = getOrCreateArrayNode(instanceRoot, name);
    arrayNode.add(value);
  }

  private ArrayNode getOrCreateArrayNode(ObjectNode instanceRoot, String name) {
    if (!instanceRoot.has(name)) {
      instanceRoot.set(name, JsonNodeFactory.instance.arrayNode());
    }

    return (ArrayNode) instanceRoot.get(name);
  }

  private void addToJsonAsSingle(ObjectNode jsonNode, String attribute, JsonNode value) {
    jsonNode.set(attribute, value);
  }

  private String literalToDate(Literal literal) {
    return date2string(ISODateTimeFormat.dateTimeParser().parseDateTime(literal.getLexicalForm()));
  }

  private String date2string(DateTime date) {
    return date == null ? null : date.toString(ISODateTimeFormat.date());
  }

  private String literalToDateTime(Literal literal) {
    return dateTime2string(ISODateTimeFormat.dateTimeParser().parseDateTime(literal.getLexicalForm()));
  }

  private String dateTime2string(DateTime dateTime) {
    return dateTime == null ? null : dateTime.withZone(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime());
  }

  private TextNode getTextNode(String value) {
    return JsonNodeFactory.instance.textNode(value);
  }

  private BooleanNode getBooleanNode(boolean value) {
    return JsonNodeFactory.instance.booleanNode(value);
  }

  private NumericNode getNumberNode(int value) {
    return JsonNodeFactory.instance.numberNode(value);
  }

  private NumericNode getNumberNode(long value) {
    return JsonNodeFactory.instance.numberNode(value);
  }

  private NumericNode getNumberNode(float value) {
    return JsonNodeFactory.instance.numberNode(value);
  }

  private NumericNode getNumberNode(double value) {
    return JsonNodeFactory.instance.numberNode(value);
  }

  private static class Context {

    private final ModelToJsonConversion parent;
    private final Model model;
    private final ObjectNode jsonRoot;
    private final Set<Resource> alreadyProcessedResources;
    private final Model alreadyProcessedModel;
    private final Map<Resource, ConversionProfile.Type> subjectTypeMap;

    public Context(ModelToJsonConversion parent, Model model) {
      this.parent = parent;
      this.model = model;

      subjectTypeMap = calculateSubjectTypeMap(model);
      alreadyProcessedResources = new HashSet<>();
      alreadyProcessedModel = ModelFactory.createDefaultModel();
      jsonRoot = JsonNodeFactory.instance.objectNode();
    }

    private Map<Resource, ConversionProfile.Type> calculateSubjectTypeMap(Model model) {
      Map<Resource, Set<String>> rdfTypesMap = calculateSubjectRdfTypesMap(model);

      Map<Resource, ConversionProfile.Type> result = new HashMap<>();
      rdfTypesMap.forEach((resource, rdfTypes) -> {
        result.put(resource, calculateType(rdfTypes));
      });
      return result;
    }

    private Map<Resource, Set<String>> calculateSubjectRdfTypesMap(Model model) {
      Map<Resource, Set<String>> subjectTypeMap = new HashMap<>();

      model.listStatements(null, RDF.type, (RDFNode) null)
           .forEachRemaining(statement -> {
             Resource subject = statement.getSubject();
             if (!subjectTypeMap.containsKey(subject)) {
               subjectTypeMap.put(subject, new HashSet<>());
             }

             String type = statement.getObject().asResource().getURI();
             subjectTypeMap.get(subject).add(type);
           });

      return subjectTypeMap;
    }

    private ConversionProfile.Type calculateType(Set<String> rdfTypes) {
      if (parent.configuration.isModelType(ModelType.ROOT)) {
        if (rdfTypes.size() != 1) throw new RuntimeException("expecting exactly one type, found " + rdfTypes);

        String rdfType = rdfTypes.stream().findFirst().get();
        return parent.conversionProfile.getTypeFromRdfType(rdfType);
      }

      if (parent.configuration.isModelType(ModelType.PROFILE)) {
        return parent.conversionProfile.getBestMatchingTypeFromRdfTypes(rdfTypes);
      }

      if (parent.configuration.isModelType(ModelType.ALL)) {
        return parent.conversionProfile.getTypeFromRdfTypes(rdfTypes);
      }

      throw new RuntimeException("should never get here");
    }

  }
}
