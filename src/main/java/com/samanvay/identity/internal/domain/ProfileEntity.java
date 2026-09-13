package com.samanvay.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "identity_profile")
public class ProfileEntity {
    @Id
    @Column(name = "citizen_id")
    private UUID citizenId;
    @Column(name = "name_latin")
    private String nameLatin;
    @Column(name = "name_devanagari")
    private String nameDevanagari;
    @Column(name = "given_name")
    private String givenName;
    @Column(name = "family_name")
    private String familyName;
    @Column(name = "father_name")
    private String fatherName;
    private LocalDate dob;
    @Column(name = "dob_precision")
    private String dobPrecision;
    private String gender;
    @Column(name = "contact_masked")
    private String contactMasked;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getCitizenId() {
        return citizenId;
    }

    public void setCitizenId(UUID citizenId) {
        this.citizenId = citizenId;
    }

    public String getNameLatin() {
        return nameLatin;
    }

    public void setNameLatin(String nameLatin) {
        this.nameLatin = nameLatin;
    }

    public String getNameDevanagari() {
        return nameDevanagari;
    }

    public void setNameDevanagari(String nameDevanagari) {
        this.nameDevanagari = nameDevanagari;
    }

    public void setGivenName(String givenName) {
        this.givenName = givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public void setFamilyName(String familyName) {
        this.familyName = familyName;
    }

    public String getFatherName() {
        return fatherName;
    }

    public void setFatherName(String fatherName) {
        this.fatherName = fatherName;
    }

    public LocalDate getDob() {
        return dob;
    }

    public void setDob(LocalDate dob) {
        this.dob = dob;
    }

    public String getDobPrecision() {
        return dobPrecision;
    }

    public void setDobPrecision(String dobPrecision) {
        this.dobPrecision = dobPrecision;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public void setContactMasked(String contactMasked) {
        this.contactMasked = contactMasked;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
