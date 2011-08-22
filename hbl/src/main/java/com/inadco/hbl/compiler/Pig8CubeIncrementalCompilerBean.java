package com.inadco.hbl.compiler;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.apache.commons.lang.Validate;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.inadco.hbl.api.Cube;
import com.inadco.hbl.api.Cuboid;
import com.inadco.hbl.api.Measure;
import com.inadco.hbl.model.HblAdmin;
import com.inadco.hbl.protocodegen.Cells.Aggregation;
import com.inadco.hbl.util.HblUtil;
import com.inadco.hbl.util.IOUtil;

/**
 * Pig8-based cube incremental compiler. Guarantees to commit hbase in
 * idempotent fashion if seen input only once (i.e. incrementally).
 * <P>
 * 
 * Warning: if run periodically, doesn't protect against nasty effects such as
 * pig memory leaks or pig non-reentrancy or job backlog overrun. It's now
 * assumed invoker's /caller's responsibility to do so .
 * <P>
 * 
 * @author dmitriy
 * 
 */
@Component("Pig8CubeIncrementalCompiler")
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class Pig8CubeIncrementalCompilerBean {

    public static final String PROP_CUBEMODEL = "com.inadco.hbl.cubemodel";

    /*
     * regex, not verbatim
     */
    public static final String SUBS_OPEN      = "$hbl:{";
    public static final String SUBS_CLOSE     = "}";

    protected Resource         cubeModel;
    protected Resource         pigPreambula;
    protected String           inputRelationName;
    protected int              parallel       = 2;

    protected Cube             cube;
    protected String           cubeModelYamlStr;

    /**
     * non-spring version
     * 
     * @param cubeModel
     *            cube model specification
     * 
     * @param pigPreambula
     *            pig script fragment that establishes the input
     * @throws IOException
     */
    public Pig8CubeIncrementalCompilerBean(Resource cubeModel, Resource pigPreambula, int parallel) throws IOException {
        this(cubeModel, pigPreambula, parallel, "HBL_INPUT");
    }

    /**
     * non-spring version
     * 
     * @param cubeModel
     *            cube model specification
     * @param pigPreambula
     *            pig script fragment that retrieves the input
     * @param relation
     *            name to use for input out of pigPreambula fragment
     */
    public Pig8CubeIncrementalCompilerBean(Resource cubeModel,
                                           Resource pigPreambula,
                                           int parallel,
                                           String inputRelationName) throws IOException {
        super();
        this.cubeModel = cubeModel;
        this.pigPreambula = pigPreambula;
        this.inputRelationName = inputRelationName;
        init();
    }

    public int getParallel() {
        return parallel;
    }

    public void setParallel(int parallel) {
        this.parallel = parallel;
    }

    /**
     * Spring constructor
     */
    public Pig8CubeIncrementalCompilerBean() {
        super();
        // TODO Auto-generated constructor stub
    }

    public Resource getCubeModel() {
        return cubeModel;
    }

    @Required
    public void setCubeModel(Resource cubeModel) {
        this.cubeModel = cubeModel;
    }

    @Required
    public Resource getPigPreambula() {
        return pigPreambula;
    }

    public void setPigPreambula(Resource preambula) {
        this.pigPreambula = preambula;
    }

    public String getInputRelationName() {
        return inputRelationName;
    }

    @Required
    public void setInputRelationName(String inputRelationName) {
        this.inputRelationName = inputRelationName;
    }

    @PostConstruct
    public void init() throws IOException {
        Validate.notNull(cubeModel);
        Validate.notNull(pigPreambula);
        Deque<Closeable> closeables = new ArrayDeque<Closeable>();
        try {
            InputStream cubeIs = cubeModel.getInputStream();
            closeables.addFirst(cubeIs);
            cubeModelYamlStr = fromStream(cubeIs, "utf-8");
            cube = YamlModelParser.parseYamlModel(cubeModelYamlStr);

        } finally {
            IOUtil.closeAll(closeables);
        }

    }

    // public void initJobParams(Configuration confTo) throws IOException {
    // Validate.notNull(cubeModel);
    // InputStream is = cubeModel.getInputStream();
    // Validate.notNull(is, "cube model resource not found");
    // try {
    // YamlModelParser.initCubeModel(fromStream(is, "utf-8"), confTo);
    // } finally {
    // is.close();
    // }
    // }

    public String preparePigSource(String workDir) throws IOException {

        Deque<Closeable> closeables = new ArrayDeque<Closeable>();
        try {
            Map<String, String> substitutes = new HashMap<String, String>();

            InputStream is =
                Pig8CubeIncrementalCompilerBean.class.getClassLoader().getResourceAsStream("hbl-compiler.pig");
            Validate.notNull(is, "hbl-compiler.pig not found");
            closeables.addFirst(is);
            String compilerSrc = fromStream(is, "utf-8");

            substitutes.put("workDir", workDir);
            substitutes.put("inputRelation", inputRelationName);
            substitutes.put("parallel", "" + parallel);
            substitutes.put("cubeName", cube.getName());

            // preambula
            generatePreambula(substitutes, closeables);

            // measures
            generateCommonDefs(substitutes, closeables);

            // $hbl:{cuboidStoreDefs}
            generateCuboidStoreDefs(substitutes, closeables);

            // cuboid bodies
            generateBody(substitutes, closeables);

            // substitute all at once
            compilerSrc = preprocess(compilerSrc, substitutes);
            return compilerSrc;

        } finally {
            IOUtil.closeAll(closeables);
        }

    }

    public static String fromStream(InputStream is, String encoding) throws IOException {
        StringWriter sw = new StringWriter();
        Reader r = new InputStreamReader(is, "utf-8");
        int ch;
        while (-1 != (ch = r.read()))
            sw.write(ch);
        sw.close();
        return sw.toString();
    }

    private void generatePreambula(Map<String, String> substitutes, Deque<Closeable> closeables) throws IOException {
        InputStream is = pigPreambula.getInputStream();
        Validate.notNull(is, "preambula not found");
        closeables.addFirst(is);
        String preambulaStr = fromStream(is, "utf-8");
        substitutes.put("preambula", preambulaStr);
    }

    private void generateCommonDefs(Map<String, String> substitutes, Deque<Closeable> closeables) throws IOException {
        Validate.notNull(cube);
        String cubeModel = YamlModelParser.encodeCubeModel(cubeModelYamlStr);
        substitutes.put("cubeModel", cubeModel);

        // String def =
        // String.format("DEFINE hbl_m2d com.inadco.hbl.piggybank.Measure2Double('%s');\n",
        // YamlModelParser.encodeCubeModel(cubeModelYamlStr));
        // substitutes.put("measure2DoubleDef", def);
        //
        // def =
        // String.format("DEFINE hbl_d2k com.inadco.hbl.piggybank.Dimensions2CuboidKey('%s');\n",
        // YamlModelParser.encodeCubeModel(cubeModelYamlStr));

    }

    private void generateCuboidStoreDefs(Map<String, String> substitutes, Deque<Closeable> closeables)
        throws IOException {

        StringBuffer sb = new StringBuffer();
        for (Cuboid cuboid : cube.getCuboids())
            sb.append(generateCuboidStorageDef(cube, cuboid));
        substitutes.put("cuboidStoreDefs", sb.toString());

    }

    private String generateCuboidStorageDef(Cube cube, Cuboid cuboid) throws IOException {
        StringBuffer sb = new StringBuffer();
        StringBuffer hbaseSpecs = new StringBuffer();

        for (Measure m : cube.getMeasures().values()) {
            hbaseSpecs.append(generateHbaseProtoStorageSpec(m));
            hbaseSpecs.append(' ');
        }
        sb.append(String.format("DEFINE store_%s com.inadco.ecoadapters.pig.HBaseProtobufStorage ('%s');\n",
                                cuboid.getCuboidTableName(),
                                hbaseSpecs.toString()));

        sb.append(String.format("DEFINE get_%s com.inadco.ecoadapters.pig.HBaseGet('%1$s', '%s');\n",
                                cuboid.getCuboidTableName(),
                                hbaseSpecs.toString()));

        return sb.toString();
    }

    private void generateBody(Map<String, String> substitutes, Deque<Closeable> closeables) throws IOException {

        InputStream bodyTemplateIs =
            Pig8CubeIncrementalCompilerBean.class.getClassLoader().getResourceAsStream("hbl-body.pig");
        Validate.notNull(bodyTemplateIs, "body template resource not found");
        closeables.addFirst(bodyTemplateIs);

        String bodyTemplate = fromStream(bodyTemplateIs, "utf-8");

        Map<String, String> bodySubstitutes = new HashMap<String, String>();
        StringBuffer sbBody = new StringBuffer();
        for (Cuboid c : cube.getCuboids()) {
            bodySubstitutes.clear();
            bodySubstitutes.putAll(substitutes);
            generateCuboidBody(bodySubstitutes, closeables, c);
            sbBody.append(preprocess(bodyTemplate, bodySubstitutes));
        }
        substitutes.put("body", sbBody.toString());
    }

    private void generateCuboidBody(Map<String, String> substitutes, Deque<Closeable> closeables, Cuboid cuboid)
        throws IOException {
        substitutes.put("cuboidPath", HblUtil.encodeCuboidPath(cuboid));
        substitutes.put("cuboidTable", cuboid.getCuboidTableName());

        // this kind of largely relies on the fact that iterating over measure values comes in the same order. 
        // todo: standardize the measure order in the codegen.
        
        // measure evaluation -- this is actually teh same for all cuboids at
        // this time.
        StringBuffer sb = new StringBuffer();
        for (Measure m : cube.getMeasures().values()) {
            if (sb.length() != 0)
                sb.append(", ");
            sb.append(String.format("hbl_m2d('%1$s',%1$s) as %1$s", m.getName()));
        }
        substitutes.put("measuresEval", sb.toString());

        sb.setLength(0);
        for (Measure m : cube.getMeasures().values()) {
            if (sb.length() != 0)
                sb.append(",");
            sb.append("\n  ");
            sb.append(generateMeasureMetricEval(m.getName(), "GROUP_" + cuboid.getCuboidTableName()));
        }
        substitutes.put("measureMetricEvals", sb.toString());

        sb.setLength(0);
        for (Measure m : cube.getMeasures().values()) {
            if (sb.length() != 0)
                sb.append(",");
            sb.append("\n  ");
            sb.append(generateMeasureMetricMerge(m.getName()));
        }
        substitutes.put("measureMetricMerges", sb.toString());

        sb.setLength(0);
        for (Measure m : cube.getMeasures().values()) {
            if (sb.length() != 0)
                sb.append(",");
            sb.append("\n  ");
            sb.append(generateMeasureMetricSchema(m.getName()));
        }
        substitutes.put("measureMetricsSchema", sb.toString());

        // dim key evaluation -- dimensions in the order of cuboid path
        sb.setLength(0);
        for (String dim : cuboid.getCuboidPath()) {
            if (sb.length() != 0)
                sb.append(", ");
            sb.append(dim);
        }

        substitutes.put("cuboidKeyEval",
                        String.format("hbl_d2k('%s', %s)", HblUtil.encodeCuboidPath(cuboid), sb.toString()));

    }

    private String generateHbaseProtoStorageSpec(Measure measure) {
        return String.format("%s:%s:%s",
                             HblAdmin.HBL_METRIC_FAMILY,
                             measure.getName(),
                             Aggregation.class.getName()
                                 .replaceAll(Pattern.quote("$"), Matcher.quoteReplacement("\\$")));
    }

    private static String preprocess(String src, Map<String, String> substitutes) {

        /*
         * this actually is not quite correct way to do it (for one, we hope all
         * names match \w and if not, then they may be treated as regexes, not
         * as constants!). Second, since it is sequential replacements, values
         * will be matched against parameter string in subsequent replacements,
         * which is not only incorrect but also creates undetermined result.
         * This really needs a more neat work such as parameter preprocessor in
         * pig, but this two-liner is the easiest and fastest thing i can do
         * right now.
         */

        for (Map.Entry<String, String> entry : substitutes.entrySet())
            src =
                src.replaceAll(Pattern.quote(SUBS_OPEN + entry.getKey() + SUBS_CLOSE),
                               Matcher.quoteReplacement(entry.getValue()));
        return src;

    }

    private static String generateMeasureMetricEval(String metricName, String groupName) {
        return String.format("TOTUPLE(SUM(%1$s.%2$s),COUNT_STAR(%1$s)) as %3$s",
                             groupName,
                             metricName,
                             generateMeasureMetricSchema(metricName));
    }

    private static String generateMeasureMetricMerge(String metricName) {
        return String.format("TOTUPLE( " + "(hbl_old.%1$s is null?%1$s.sum:hbl_old.%1$s.sum+%1$s.sum),"
                                 + "(hbl_old.%1$s is null?%1$s.cnt:hbl_old.%1$s.cnt+%1$s.cnt)" + ") as %2$s",
                             metricName,
                             generateMeasureMetricSchema(metricName));
    }

    public static String generateMeasureMetricSchema(String metricName) {
        return String.format("%s:(sum:double,cnt:long)",metricName);
    }

}
