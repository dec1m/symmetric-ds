package org.jumpmind.symmetric.load;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.jumpmind.db.model.Table;
import org.jumpmind.extension.IBuiltInExtensionPoint;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.SymmetricException;
import org.jumpmind.symmetric.io.data.CsvData;
import org.jumpmind.symmetric.io.data.DataContext;
import org.jumpmind.symmetric.io.data.DataEventType;
import org.jumpmind.symmetric.io.data.writer.IDatabaseWriterErrorHandler;
import org.jumpmind.symmetric.io.data.writer.IDatabaseWriterFilter;
import org.jumpmind.symmetric.model.LoadFilter;
import org.jumpmind.util.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bsh.EvalError;
import bsh.Interpreter;

public class BshDatabaseWriterFilter implements IDatabaseWriterFilter, IDatabaseWriterErrorHandler,
        IBuiltInExtensionPoint {

    private static final String OLD_ = "OLD_";
    private static final String CONTEXT = "context";
    private static final String TABLE = "table";
    private static final String DATA = "data";
    private static final String ENGINE = "engine";
    private final String INTERPRETER_KEY = String.format("%d.BshInterpreter", hashCode());
    private final String BATCH_COMPLETE_SCRIPTS_KEY = String.format("%d.BatchCompleteScripts",
            hashCode());
    private final String BATCH_COMMIT_SCRIPTS_KEY = String.format("%d.BatchCommitScripts",
            hashCode());
    private final String BATCH_ROLLBACK_SCRIPTS_KEY = String.format("%d.BatchRollbackScripts",
            hashCode());
    private final String FAIL_ON_ERROR_KEY = String.format("%d.FailOnError", hashCode());

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected ISymmetricEngine symmetricEngine = null;

    protected Map<String, List<LoadFilter>> loadFilters = null;

    public enum WriteMethod {
        BEFORE_WRITE, AFTER_WRITE, BATCH_COMPLETE, BATCH_COMMIT, BATCH_ROLLBACK, HANDLE_ERROR
    };

    public BshDatabaseWriterFilter(ISymmetricEngine symmetricEngine,
            Map<String, List<LoadFilter>> loadFilters) {

        this.symmetricEngine = symmetricEngine;
        this.loadFilters = loadFilters;
    }

    public boolean beforeWrite(DataContext context, Table table, CsvData data) {
        return processLoadFilters(context, table, data, WriteMethod.BEFORE_WRITE);
    }

    public void afterWrite(DataContext context, Table table, CsvData data) {
        processLoadFilters(context, table, data, WriteMethod.AFTER_WRITE);
    }

    public boolean handleError(DataContext context, Table table, CsvData data, Exception ex) {
        return processLoadFilters(context, table, data, WriteMethod.HANDLE_ERROR);
    }

    public boolean handlesMissingTable(DataContext context, Table table) {
        return false;
    }

    public void earlyCommit(DataContext context) {
    }

    public void batchComplete(DataContext context) {
        executeScripts(context, BATCH_COMPLETE_SCRIPTS_KEY);
    }

    public void batchCommitted(DataContext context) {
        executeScripts(context, BATCH_COMMIT_SCRIPTS_KEY);
    }

    public void batchRolledback(DataContext context) {
        executeScripts(context, BATCH_ROLLBACK_SCRIPTS_KEY);
    }

    protected Interpreter getInterpreter(Context context) {
        Interpreter interpreter = (Interpreter) context.get(INTERPRETER_KEY);
        if (interpreter == null) {
            interpreter = new Interpreter();
            context.put(INTERPRETER_KEY, interpreter);
        }
        return interpreter;
    }

    protected void bind(Interpreter interpreter, DataContext context, Table table, CsvData data)
            throws EvalError {

        interpreter.set(ENGINE, this.symmetricEngine);
        interpreter.set(CONTEXT, context);
        interpreter.set(TABLE, table);
        interpreter.set(DATA, data);

        if (data != null) {
            Map<String, String> sourceValues = data.toColumnNameValuePairs(table.getColumnNames(),
                    CsvData.ROW_DATA);
            for (String columnName : sourceValues.keySet()) {
                interpreter.set(columnName.toUpperCase(), sourceValues.get(columnName));
            }

            Map<String, String> oldValues = data.toColumnNameValuePairs(table.getColumnNames(),
                    CsvData.OLD_DATA);
            for (String columnName : oldValues.keySet()) {
                interpreter.set(OLD_ + columnName.toUpperCase(), sourceValues.get(columnName));
            }
        }

    }

    protected void processError(LoadFilter currentFilter, Table table, String errorMsg) {
        String formattedMessage = String.format(
                "Error executing beanshell script for load filter %s on table %s error %s",
                new Object[] { currentFilter != null ? currentFilter.getLoadFilterId() : "N/A",
                        table.getName(), errorMsg });
        log.error(formattedMessage);
        if (currentFilter.isFailOnError()) {
            throw new SymmetricException(formattedMessage);
        }
    }

    protected void addBatchScriptsToContext(DataContext context, LoadFilter filter) {
        addBatchScriptToContext(context, BATCH_COMPLETE_SCRIPTS_KEY,
                filter.getBatchCompleteScript());
        addBatchScriptToContext(context, BATCH_COMMIT_SCRIPTS_KEY, filter.getBatchCommitScript());
        addBatchScriptToContext(context, BATCH_ROLLBACK_SCRIPTS_KEY,
                filter.getBatchRollbackScript());
        if (filter.isFailOnError()) {
            context.put(FAIL_ON_ERROR_KEY, Boolean.TRUE);
        }
    }

    protected void addBatchScriptToContext(DataContext context, String key, String script) {
        if (StringUtils.isNotBlank(script)) {
            @SuppressWarnings("unchecked")
            Set<String> scripts = (Set<String>) context.get(key);
            if (scripts == null) {
                scripts = new HashSet<String>();
                context.put(key, scripts);
            }
            scripts.add(script);
        }
    }

    protected void executeScripts(DataContext context, String key) {
        @SuppressWarnings("unchecked")
        Set<String> scripts = (Set<String>) context.get(key);
        Interpreter interpreter = getInterpreter(context);
        String currentScript = null;
        try {
            bind(interpreter, context, null, null);
            for (String script : scripts) {
                currentScript = script;
                interpreter.eval(script);
            }
        } catch (EvalError e) {
            String errorMsg = String.format("Beanshell script %s with error %s", new Object[] {
                    currentScript, e.getErrorText() });
            log.error(errorMsg);
            if (BooleanUtils.isTrue((Boolean) context.get(FAIL_ON_ERROR_KEY))) {
                throw new SymmetricException(errorMsg);
            }
        }

    }

    protected boolean processLoadFilters(DataContext context, Table table, CsvData data,
            WriteMethod writeMethod) {

        boolean writeRow = true;
        LoadFilter currentFilter = null;

        List<LoadFilter> loadFiltersForTable = loadFilters.get(table.getFullyQualifiedTableName());

        if (loadFiltersForTable != null && loadFiltersForTable.size() > 0) {
            try {
                Interpreter interpreter = getInterpreter(context);
                bind(interpreter, context, table, data);
                for (LoadFilter filter : loadFiltersForTable) {
                    currentFilter = filter;
                    addBatchScriptsToContext(context, filter);
                    if (filter.isFilterOnDelete()
                            && data.getDataEventType().equals(DataEventType.DELETE)
                            || filter.isFilterOnInsert()
                            && data.getDataEventType().equals(DataEventType.INSERT)
                            || filter.isFilterOnUpdate()
                            && data.getDataEventType().equals(DataEventType.UPDATE)) {
                        Object result = null;
                        if (writeMethod.equals(WriteMethod.BEFORE_WRITE)
                                && filter.getBeforeWriteScript() != null) {
                            result = interpreter.eval(filter.getBeforeWriteScript());
                        } else if (writeMethod.equals(WriteMethod.AFTER_WRITE)
                                && filter.getAfterWriteScript() != null) {
                            result = interpreter.eval(filter.getAfterWriteScript());
                        } else if (writeMethod.equals(WriteMethod.HANDLE_ERROR)
                                && filter.getHandleErrorScript() != null) {
                            result = interpreter.eval(filter.getHandleErrorScript());
                        }

                        if (result != null && result.equals(Boolean.FALSE)) {
                            writeRow = false;
                        }
                    }
                }
            } catch (EvalError evalEx) {
                processError(currentFilter, table, evalEx.getErrorText());
            }
        }

        return writeRow;
    }
}
