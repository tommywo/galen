/*******************************************************************************
* Copyright 2015 Ivan Shubin http://galenframework.com
* 
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
*   http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
******************************************************************************/
package com.galenframework;

import static java.util.Arrays.asList;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.galenframework.browser.Browser;
import com.galenframework.parser.SyntaxException;
import com.galenframework.reports.ConsoleReportingListener;
import com.galenframework.reports.GalenTestInfo;
import com.galenframework.reports.HtmlReportBuilder;
import com.galenframework.runner.GalenArguments;
import com.galenframework.runner.JsTestCollector;
import com.galenframework.runner.SuiteListener;
import com.galenframework.runner.events.TestFilterEvent;
import com.galenframework.suite.actions.GalenPageActionCheck;
import com.galenframework.suite.reader.GalenSuiteReader;
import com.galenframework.tests.GalenTest;
import com.galenframework.validation.FailureListener;
import com.galenframework.api.Galen;
import com.galenframework.browser.Browser;
import com.galenframework.browser.SeleniumBrowserFactory;
import com.galenframework.config.GalenConfig;
import com.galenframework.javascript.GalenJsExecutor;
import com.galenframework.parser.SyntaxException;
import com.galenframework.reports.ConsoleReportingListener;
import com.galenframework.reports.GalenTestInfo;
import com.galenframework.reports.HtmlReportBuilder;
import com.galenframework.reports.TestNgReportBuilder;
import com.galenframework.reports.json.JsonReportBuilder;
import com.galenframework.reports.model.FileTempStorage;
import com.galenframework.runner.CombinedListener;
import com.galenframework.runner.CompleteListener;
import com.galenframework.runner.EventHandler;
import com.galenframework.runner.GalenArguments;
import com.galenframework.runner.JsTestCollector;
import com.galenframework.runner.SuiteListener;
import com.galenframework.runner.events.TestFilterEvent;
import com.galenframework.suite.GalenPageAction;
import com.galenframework.suite.GalenPageTest;
import com.galenframework.suite.actions.GalenPageActionCheck;
import com.galenframework.suite.reader.GalenSuiteReader;
import com.galenframework.tests.GalenBasicTest;
import com.galenframework.tests.GalenTest;
import com.galenframework.validation.FailureListener;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GalenMain {

    private final static Logger LOG = LoggerFactory.getLogger(GalenMain.class);
    private static final Map<String, Object> NO_JSVARIABLES = null;

    private CompleteListener listener;

    public void execute(GalenArguments arguments) throws Exception {
        if (arguments.getAction() != null) {

            FailureListener failureListener = new FailureListener();
            CombinedListener combinedListener = createListeners(arguments);
            combinedListener.add(failureListener);
            if (listener != null) {
                combinedListener.add(listener);
            }

            if ("test".equals(arguments.getAction())) {
                runTests(arguments, combinedListener);
            } else if ("check".equals(arguments.getAction())) {
                performCheck(arguments, combinedListener);
            } else if ("config".equals(arguments.getAction())) {
                performConfig();
            } else if ("dump".equals(arguments.getAction())) {
                performPageDump(arguments);
            }
            combinedListener.done();

            if (GalenConfig.getConfig().getUseFailExitCode()) {
                if (failureListener.hasFailures()) {
                    System.err.println("There were failures in galen tests");
                    System.exit(1);
                }
            }
        } else {
            if (arguments.getPrintVersion()) {
                System.out.println("Galen Framework");
                String version = getClass().getPackage().getImplementationVersion();
                if (version == null) {
                    version = "unknown";
                } else {
                    version = version.replace("-SNAPSHOT", "");
                }
                System.out.println("Version: " + version);
                System.out.println("JavaScript executor: " + GalenJsExecutor.getVersion());
            }
        }
    }

    private void performPageDump(GalenArguments arguments) throws SyntaxException {
        SeleniumBrowserFactory browserFactory = new SeleniumBrowserFactory();
        Browser browser = browserFactory.openBrowser();

        try {

            if (arguments.getUrl() == null || arguments.getUrl().isEmpty()) {
                throw new SyntaxException("--url parameter is not defined");
            }
            if (arguments.getPaths() == null || arguments.getPaths().size() == 0) {
                throw new SyntaxException("You should specify a spec file with which you want to make a page dump");
            }
            if (arguments.getExport() == null || arguments.getExport().isEmpty()) {
                throw new SyntaxException("--export parameter is not defined");
            }

            if (arguments.getScreenSize() != null) {
                browser.changeWindowSize(arguments.getScreenSize());
            }

            browser.load(arguments.getUrl());

            Galen.dumpPage(browser, arguments.getUrl(), arguments.getPaths().get(0), arguments.getExport(), arguments.getMaxWidth(), arguments.getMaxHeight(), false, NO_JSVARIABLES);
            System.out.println("Done!");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            browser.quit();
        }
    }

    public void performConfig() throws IOException {
        File file = new File("config");

        if (!file.exists()) {
            file.createNewFile();
            FileOutputStream fos = new FileOutputStream(file);

            StringWriter writer = new StringWriter();
            IOUtils.copy(getClass().getResourceAsStream("/config-template.conf"), writer, "UTF-8");
            IOUtils.write(writer.toString(), fos, "UTF-8");
            fos.flush();
            fos.close();
            System.out.println("Created config file");
        } else {
            System.err.println("Config file already exists");
        }
    }

    private CombinedListener createListeners(GalenArguments arguments) throws IOException, SecurityException, IllegalArgumentException, ClassNotFoundException,
            NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
        CombinedListener combinedListener = new CombinedListener();
        combinedListener.add(new ConsoleReportingListener(System.out, System.out));

        // Adding all user defined listeners
        List<CompleteListener> configuredListeners = getConfiguredListeners();
        for (CompleteListener configuredListener : configuredListeners) {
            combinedListener.add(configuredListener);
        }
        return combinedListener;
    }

    @SuppressWarnings("unchecked")
    public List<CompleteListener> getConfiguredListeners() throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
            InstantiationException, IllegalAccessException, InvocationTargetException {
        List<CompleteListener> configuredListeners = new LinkedList<CompleteListener>();
        List<String> classNames = GalenConfig.getConfig().getReportingListeners();

        for (String className : classNames) {
            Constructor<CompleteListener> constructor = (Constructor<CompleteListener>) Class.forName(className).getConstructor();
            configuredListeners.add(constructor.newInstance());
        }
        return configuredListeners;
    }

    private void performCheck(GalenArguments arguments, CombinedListener listener) throws IOException {
        verifyArgumentsForPageCheck(arguments);

        List<GalenTest> galenTests = new LinkedList<GalenTest>();

        for (String pageSpecPath : arguments.getPaths()) {
            GalenBasicTest test = new GalenBasicTest();
            test.setName(pageSpecPath);
            test.setPageTests(asList(new GalenPageTest()
                    .withTitle("Simple check")
                    .withUrl(arguments.getUrl())
                    .withSize(arguments.getScreenSize())
                    .withBrowserFactory(new SeleniumBrowserFactory())
                    .withActions(
                            asList((GalenPageAction) new GalenPageActionCheck().withSpec(pageSpecPath).withIncludedTags(arguments.getIncludedTags())
                                    .withExcludedTags(arguments.getExcludedTags()).withOriginalCommand(arguments.getOriginal())))));
            galenTests.add(test);
        }

        runTests(new EventHandler(), arguments, galenTests, listener);
    }

    private void verifyArgumentsForPageCheck(GalenArguments arguments) {
        if (arguments.getUrl() == null) {
            throw new IllegalArgumentException("Url is not specified");
        }

        if (arguments.getScreenSize() == null) {
            throw new IllegalArgumentException("Screen size is not specified");
        }

        if (arguments.getPaths().size() < 1) {
            throw new IllegalArgumentException("There are no specs specified");
        }

    }

    public static void main(String[] args) throws Exception {
        new GalenMain().execute(GalenArguments.parse(args));
    }

    private void runTests(GalenArguments arguments, CompleteListener listener) throws IOException {
        List<File> basicTestFiles = new LinkedList<File>();
        List<File> jsTestFiles = new LinkedList<File>();

        for (String path : arguments.getPaths()) {
            File file = new File(path);
            if (file.exists()) {
                if (file.isDirectory()) {
                    searchForTests(file, arguments.getRecursive(), basicTestFiles, jsTestFiles);
                } else if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(GalenConfig.getConfig().getTestSuffix())) {
                        basicTestFiles.add(file);
                    } else if (name.endsWith(".js")) {
                        jsTestFiles.add(file);
                    }
                }
            } else {
                throw new FileNotFoundException(path);
            }
        }

        if (basicTestFiles.size() > 0 || jsTestFiles.size() > 0) {
            runTestFiles(basicTestFiles, jsTestFiles, listener, arguments);
        } else {
            throw new RuntimeException("Couldn't find any test files");
        }
    }

    private void runTestFiles(List<File> basicTestFiles, List<File> jsTestFiles, CompleteListener listener, GalenArguments arguments) throws IOException {
        GalenSuiteReader reader = new GalenSuiteReader();

        List<GalenTest> tests = new LinkedList<GalenTest>();
        for (File file : basicTestFiles) {
            tests.addAll(reader.read(file));
        }

        JsTestCollector testCollector = new JsTestCollector(tests);
        for (File jsFile : jsTestFiles) {
            testCollector.execute(jsFile);
        }

        testCollector.getEventHandler().invokeBeforeTestSuiteEvents();

        runTests(testCollector.getEventHandler(), arguments, tests, listener);

        testCollector.getEventHandler().invokeAfterTestSuiteEvents();
    }

    private void runTests(EventHandler eventHandler, GalenArguments arguments, List<GalenTest> tests, CompleteListener listener) {

        if (arguments.getParallelSuites() > 1) {
            runTestsInThreads(eventHandler, tests, arguments, listener, arguments.getParallelSuites());
        } else {
            runTestsInThreads(eventHandler, tests, arguments, listener, 1);
        }
    }

    private void runTestsInThreads(final EventHandler eventHandler, List<GalenTest> tests, GalenArguments arguments, final CompleteListener listener,
                                   int amountOfThreads) {
        ExecutorService executor = Executors.newFixedThreadPool(amountOfThreads);

        Pattern filterPattern = createTestFilter(arguments.getFilter());

        List<GalenTest> filteredTests = filterTests(tests, eventHandler);

        tellBeforeTestSuite(listener, filteredTests);

        List<GalenTestInfo> testInfos = Collections.synchronizedList(new LinkedList<GalenTestInfo>());

        for (final GalenTest test : filteredTests) {
            if (matchesPattern(test.getName(), filterPattern)
                    && matchesSelectedGroups(test, arguments.getGroups())
                    && doesNotMatchExcludedGroups(test, arguments.getExcludedGroups())) {
                executor.execute(new TestRunnable(test, listener, eventHandler, testInfos));
            }
        }
        executor.shutdown();
        while (!executor.isTerminated()) {
        }

        tellAfterTestSuite(listener, testInfos);

        createAllReports(testInfos, arguments);

        cleanData(testInfos);
    }

    private void cleanData(List<GalenTestInfo> testInfos) {
        for (GalenTestInfo testInfo : testInfos) {
            if (testInfo.getReport() != null) {
                FileTempStorage storage = testInfo.getReport().getFileStorage();
                if (storage != null) {
                    storage.cleanup();
                }
            }
        }
    }

    private boolean doesNotMatchExcludedGroups(GalenTest test, List<String> excludedGroups) {
        if (excludedGroups != null && excludedGroups.size() > 0) {
            return !matchesSelectedGroups(test, excludedGroups);
        }
        return true;
    }

    private boolean matchesSelectedGroups(GalenTest test, List<String> selectedGroups) {
        if (selectedGroups != null && selectedGroups.size() > 0) {
            List<String> testGroups = test.getGroups();

            if (testGroups != null && testGroups.size() > 0) {
                for (String testGroup : testGroups) {
                    if (selectedGroups.contains(testGroup)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return true;
    }

    private List<GalenTest> filterTests(List<GalenTest> tests, EventHandler eventHandler) {
        List<TestFilterEvent> filters = eventHandler.getTestFilterEvents();
        if (filters != null && filters.size() > 0) {
            GalenTest[] arrTests = tests.toArray(new GalenTest[]{});

            for (TestFilterEvent filter : filters) {
                arrTests = filter.execute(arrTests);
            }

            if (arrTests == null) {
                arrTests = new GalenTest[]{};
            }

            return asList(arrTests);
        } else {
            return tests;
        }
    }

    private void tellBeforeTestSuite(CompleteListener listener, List<GalenTest> tests) {
        if (listener != null) {
            try {
                listener.beforeTestSuite(tests);
            } catch (Exception ex) {
                LOG.error("Unknow error before running testsuites.", ex);
            }
        }
    }

    private void tellAfterTestSuite(SuiteListener listener, List<GalenTestInfo> testInfos) {
        if (listener != null) {
            try {
                listener.afterTestSuite(testInfos);
            } catch (Exception ex) {
                LOG.error("Unknow error after running testsuites.", ex);
            }
        }
    }

    private void createAllReports(List<GalenTestInfo> testInfos, GalenArguments arguments) {
        if (arguments.getTestngReport() != null) {
            createTestngReport(arguments.getTestngReport(), testInfos);
        }
        if (arguments.getHtmlReport() != null) {
            createHtmlReport(arguments.getHtmlReport(), testInfos);
        }
        if (arguments.getJsonReport() != null) {
            createJsonReport(arguments.getJsonReport(), testInfos);
        }
    }

    private void createJsonReport(String jsonReport, List<GalenTestInfo> testInfos) {
        try {
            new JsonReportBuilder().build(testInfos, jsonReport);
        } catch (IOException e) {
            LOG.error("Failed generating json report", e);
        }
    }

    private void createHtmlReport(String htmlReportPath, List<GalenTestInfo> testInfos) {
        try {
            new HtmlReportBuilder().build(testInfos, htmlReportPath);
        } catch (Exception ex) {
            LOG.error("Unknown error during creating HTML report.", ex);
        }
    }

    private void createTestngReport(String testngReport, List<GalenTestInfo> testInfos) {
        try {
            new TestNgReportBuilder().build(testInfos, testngReport);
        } catch (Exception ex) {
            LOG.error("Unknown error during creating TestNG report.", ex);
        }
    }

    private boolean matchesPattern(String name, Pattern filterPattern) {
        if (filterPattern != null) {
            return filterPattern.matcher(name).matches();
        } else
            return true;
    }

    private Pattern createTestFilter(String filter) {
        return filter != null ? Pattern.compile(filter.replace("*", ".*")) : null;
    }

    private void searchForTests(File file, boolean recursive, List<File> files, List<File> jsFiles) {
        searchForTests(file, recursive, files, jsFiles, 0);
    }

    private void searchForTests(File file, boolean recursive, List<File> files, List<File> jsFiles, int level) {

        String fileName = file.getName().toLowerCase();
        if (file.isFile()) {
            if (fileName.endsWith(GalenConfig.getConfig().getTestSuffix())) {
                files.add(file);
            } else if (fileName.endsWith(GalenConfig.getConfig().getTestJsSuffix())) {
                jsFiles.add(file);
            }
        } else if (file.isDirectory() && (level == 0 || recursive)) {
            for (File childFile : file.listFiles()) {
                searchForTests(childFile, recursive, files, jsFiles, level + 1);
            }
        }
    }

    public CompleteListener getListener() {
        return listener;
    }

    public void setListener(CompleteListener listener) {
        this.listener = listener;
    }

}
