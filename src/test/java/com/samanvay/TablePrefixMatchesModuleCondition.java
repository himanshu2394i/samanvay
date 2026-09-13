package com.samanvay;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Table;

final class TablePrefixMatchesModuleCondition extends ArchCondition<JavaClass> {

    TablePrefixMatchesModuleCondition() {
        super("only reference tables prefixed by their owning module");
    }

    @Override
    public void check(JavaClass javaClass, ConditionEvents events) {
        String module = moduleName(javaClass.getPackageName());
        if (module == null) {
            events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " is not under com.samanvay.<module>"));
            return;
        }
        if (!javaClass.isAnnotatedWith(Table.class)) {
            events.add(SimpleConditionEvent.satisfied(
                    javaClass, javaClass.getName() + " has no @Table (JdbcTemplate / no JPA entity)"));
            return;
        }
        Table table = javaClass.getAnnotationOfType(Table.class);
        boolean ok = !table.schema().isBlank()
                ? module.equals(table.schema())
                : table.name().startsWith(module + "_");
        events.add(new SimpleConditionEvent(
                javaClass,
                ok,
                javaClass.getName() + " table " + qualified(table) + " must belong to module " + module));
    }

    private static String qualified(Table table) {
        return table.schema().isBlank() ? table.name() : table.schema() + "." + table.name();
    }

    private static String moduleName(String pkg) {
        String prefix = "com.samanvay.";
        if (!pkg.startsWith(prefix)) {
            return null;
        }
        String rest = pkg.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
