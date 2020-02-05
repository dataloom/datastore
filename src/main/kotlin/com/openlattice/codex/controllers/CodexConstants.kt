package com.openlattice.codex.controllers

import org.apache.olingo.commons.api.edm.FullQualifiedName

class CodexConstants() {

    companion object {
        const val APP_NAME = "codex"
    }

    enum class AppType(val fqn: FullQualifiedName) {
        PEOPLE(FullQualifiedName("app.people")),
        MESSAGES(FullQualifiedName("app.messages")),
        CONTACT_INFO(FullQualifiedName("app.contactinformation")),
        SETTINGS(FullQualifiedName("app.settings")),
        SENT_FROM(FullQualifiedName("app.sentfrom")),
        SENT_TO(FullQualifiedName("app.sentto")),
        SUBJECT_OF(FullQualifiedName("app.subjectof")),
    }

    enum class PropertyType(val fqn: FullQualifiedName) {
        ID(FullQualifiedName("ol.id")),
        PHONE_NUMBER(FullQualifiedName("contact.phonenumber")),
        TEXT(FullQualifiedName("ol.text")),
        TYPE(FullQualifiedName("ol.type")),
        DATE_TIME(FullQualifiedName("general.datetime")),
        WAS_DELIVERED(FullQualifiedName("ol.delivered")),
        CHANNEL(FullQualifiedName("ol.channel"))
    }

    enum class Request(val parameter: String) {
        SID("MessageSid"),
        FROM("From"),
        TO("To"),
        BODY("Body"),
        STATUS("MessageStatus")
    }
}