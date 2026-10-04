package in.samanvay.departments.kit;

import java.time.LocalDate;

/** A citizen as the department knows them. {@code dob} may be null when the department does not hold it. */
public record Person(String personId, String name, LocalDate dob) {}
