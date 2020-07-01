package com.sprint.annotation.model;

import com.sprint.annotation.RdfProperty;
import com.sprint.annotation.RdfsClass;
import com.sun.codemodel.*;
import com.sun.tools.xjc.api.S2JJAXBModel;
import com.sun.tools.xjc.api.SchemaCompiler;
import com.sun.tools.xjc.api.XJC;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.Iterator;
import java.util.Map;

public class JavaCodeMan {
    private JCodeModel jcm = null;
    private final File targetPath;
    private Iterator<JDefinedClass> classes= null;

    /**
     * Java code manipulator is instantiated
     *
     * @param targetPath directory where class source will be generated
     */
    public JavaCodeMan(final String targetPath)  {
        this.targetPath = new File(targetPath);
    }


    /**
     * Generates JCodeModel and stores it for future annotation and final build/write down to targetPath
     *
     * @param schemaPath File path related to the xsd standard
     * @throws Exception failure during model generation
     */
    public void generateFromSchema(final String schemaPath) throws Exception {
        File schemaFile = new File(schemaPath);
        final SchemaCompiler sc = XJC.createSchemaCompiler();
        final FileInputStream schemaStream = new FileInputStream(schemaFile);
        final InputSource is = new InputSource(schemaStream);
        is.setSystemId(schemaFile.getAbsolutePath());

        sc.parseSchema(is);
        sc.setDefaultPackageName(null);

        final S2JJAXBModel s2 = sc.bind();
        this.jcm =  s2.generateCode(null, null);

        setPackageName(find_URI(schemaFile));

    }

    /**
     * Find targetNamespace URI of the actual standard file selected
     *
     * @param schemaFile File object related to the xsd standard file
     */
    private String find_URI(File schemaFile) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(schemaFile);
        NodeList list = doc.getElementsByTagName("xsd:schema");
        Element node = (Element)list.item(0);

        return node.getAttribute("targetNamespace");
    }


    /**
     * Before actual annotation we need to identify the correct package
     * amongst all the packages produced by the schema compiler
     *
     * @param URI namespace of schemaFile
     */
    private void setPackageName(String URI) {
        String package_name = NameConverter.standard.toPackageName(URI);
        Iterator<JPackage> itr = this.jcm.packages();
        while (itr.hasNext()) {
            JPackage t = itr.next();
            if (!t.name().equals(package_name))
                itr.remove();
        }

        this.loadClasses(package_name);

    }

    /**
     * Loads classes of the package to be annotated
     *
     * @param package_name fully qualified package name, ex. org.sprint.annotation.model
     */
    private void loadClasses(String package_name) {
        JPackage pck = this.jcm._package(package_name);
        Iterator<JDefinedClass> itr = pck.classes();
        ArrayList<JDefinedClass> jDefinedClasses = new ArrayList<>();

        while (itr.hasNext()) {
            JDefinedClass t = itr.next();
            if (!t.name().equals("ObjectFactory"))
                jDefinedClasses.add(t);
        }
        this.classes = jDefinedClasses.iterator();
    }

    /**
     * Write down of the annotated JCodeModel to targetPath
     *
     * @throws IOException failure during build
     */
    public void build() throws IOException {
        try (PrintStream status = new PrintStream(new ByteArrayOutputStream())) {
            this.jcm.build(this.targetPath, status);
        }
    }

    /**
     * Insert confirmed mapping as a annotation into the JCodeModel tree
     *
     * @param standard_name element to map to concept (ex. 'fareTravelUrl')
     * @param reference_name the reference concept in the target ontology (ex. 'st4rt:Travel')
     * @param reference_type type of concept, must be 'C' if reference_name is a class
     *                       in the ontology, 'P' if it is a property
     * @throws ClassNotFoundException cannot find element to map
     * @throws InputMismatchException trying to annotate a class as if it is a property or viceversa
     */
    public void writeDownAnnotation(String standard_name, String reference_name, char reference_type) throws ClassNotFoundException, InputMismatchException {
        JAnnotatable annotatable = this.searchByName(standard_name);
        if (annotatable instanceof JDefinedClass && reference_type == 'C')
            annotatable.annotate(jcm.ref(RdfsClass.class)).param("value", reference_name);
        else if (annotatable instanceof JFieldVar && reference_type == 'P')
            annotatable.annotate(jcm.ref(RdfProperty.class)).param("propertyName", reference_name);
        else
            throw new InputMismatchException();

    }

    private JAnnotatable searchByName(String name) throws ClassNotFoundException {
        JAnnotatable annotable = getClassByName(name, classes);
        if (annotable == null)
            throw new ClassNotFoundException();
        else {
            return annotable;
        }
    }

    private JAnnotatable getClassByName(String name, Iterator<JDefinedClass> itr) {
        while (itr.hasNext()) {
            JDefinedClass jclass = itr.next();
            JAnnotationUse ju;
            if ((ju = getAnnotation(jclass, jcm.ref(XmlType.class))) != null) {
                if (annotationEqualsName(ju, name))
                    return jclass;
            }

            JAnnotatable field = getFieldByName(name, jclass);
            if (field != null) return field;


            JAnnotatable clazz = getClassByName(name, jclass.classes());
            if (clazz != null) return clazz;

        }
        return null;
    }



    private JAnnotatable getFieldByName(String name, JDefinedClass jclass) {
        JAnnotationUse ju;
        Map<String, JFieldVar> fields = jclass.fields();
        // using for-each loop for iteration over Map.entrySet()
        for (Map.Entry<String,JFieldVar> entry : fields.entrySet()) {
            JFieldVar field = entry.getValue();
            if ((ju = getAnnotation(field, jcm.ref(XmlElement.class))) != null) {
                if (annotationEqualsName(ju, name))
                    return field;
            }
            if ((ju = getAnnotation(field, jcm.ref(XmlAttribute.class))) != null) {
                if (annotationEqualsName(ju, name))
                    return field;
            }
        }
        return null;
    }


    protected static Boolean annotationEqualsName(JAnnotationUse ju, String name) {
        JAnnotationValue ns = ju.getAnnotationMembers().get("name");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        JFormatter jf = new JFormatter(pw, "");
        ns.generate(jf);
        pw.flush();
        String s = sw.toString();
        return s.substring(1, s.length()-1).equals(name);
    }

    protected static JAnnotationUse getAnnotation(JAnnotatable annotatable, JClass annotationClass) {
        for (JAnnotationUse annotation : annotatable.annotations()) {
            if (annotation.getAnnotationClass().equals(annotationClass)) {
                return annotation;
            }
        }
        return null;
    }

    public int test() {
        if (jcm != null) {
            return jcm.countArtifacts();
        }
        return -1;
    }

/*    private void add_fold(final String schemaPath) throws Exception {
        File schemaFile = new File(schemaPath);
        JCodeModel new_branch = generateFromSchema(schemaFile);
        if (this.jcm == null) {
            this.jcm = new_branch;
            return;
        }
        final SchemaCompiler sc = XJC.createSchemaCompiler();
        final FileInputStream schemaStream = new FileInputStream(schemaFile);
        final InputSource is = new InputSource(schemaStream);
        is.setSystemId(schemaFile.getAbsolutePath());

        sc.parseSchema(is);
        sc.setDefaultPackageName(null);

        final S2JJAXBModel s2 = sc.bind();
        JCodeModel jcm_ = s2.generateCode(null, null);

    }*/

}

