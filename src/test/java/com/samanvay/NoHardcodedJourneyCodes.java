package com.samanvay;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class NoHardcodedJourneyCodes extends ArchCondition<JavaClass> {

    static final List<String> LITERALS =
            List.of("POST_MATRIC_SCHOLARSHIP", "BUSINESS_NOC", "FARMER_SUBSIDY", "SCHOLARSHIP");

    NoHardcodedJourneyCodes() {
        super("not hardcode journey or department codes");
    }

    @Override
    public void check(JavaClass javaClass, ConditionEvents events) {
        String bytecode = readClassBytes(javaClass);
        if (bytecode == null) {
            events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " could not be inspected"));
            return;
        }
        for (String literal : LITERALS) {
            if (bytecode.contains(literal)) {
                events.add(SimpleConditionEvent.violated(
                        javaClass, javaClass.getName() + " hardcodes " + literal));
            }
        }
    }

    private static String readClassBytes(JavaClass javaClass) {
        String resource = javaClass.getName().replace('.', '/') + ".class";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = NoHardcodedJourneyCodes.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return null;
        }
    }
}
