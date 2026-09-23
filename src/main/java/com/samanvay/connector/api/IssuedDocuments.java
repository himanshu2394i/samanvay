package com.samanvay.connector.api;

import java.util.List;

public interface IssuedDocuments {

    List<LockerDocument> lockerForDepartment(String departmentCode);

    IssuedRecord preview(String stepCode, String departmentCode, String stepStatus);
}
