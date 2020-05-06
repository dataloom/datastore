package com.openlattice.codex

import com.auth0.json.mgmt.users.User
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IMap
import com.openlattice.apps.AppConfigKey
import com.openlattice.apps.AppTypeSetting
import com.openlattice.codex.controllers.CodexConstants
import com.openlattice.controllers.exceptions.BadRequestException
import com.openlattice.data.DataEdge
import com.openlattice.data.DataGraphManager
import com.openlattice.data.EntityDataKey
import com.openlattice.data.EntityKeyIdService
import com.openlattice.datastore.apps.services.AppService
import com.openlattice.datastore.services.EdmManager
import com.openlattice.edm.type.PropertyType
import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.hazelcast.HazelcastQueue
import com.openlattice.organizations.HazelcastOrganizationService
import com.openlattice.organizations.roles.SecurePrincipalsManager
import com.openlattice.twilio.TwilioConfiguration
import com.twilio.Twilio
import com.twilio.rest.api.v2010.account.Message
import com.twilio.type.PhoneNumber
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.stream.Stream
import javax.servlet.http.HttpServletRequest

@Service
class CodexService(
        val twilioConfiguration: TwilioConfiguration,
        val hazelcast: HazelcastInstance,
        val appService: AppService,
        val edmManager: EdmManager,
        val dataGraphManager: DataGraphManager,
        val entityKeyIdService: EntityKeyIdService,
        val principalsManager: SecurePrincipalsManager,
        val organizations: HazelcastOrganizationService,
        val executor: ListeningExecutorService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CodexService::class.java)
        private val encoder: Base64.Encoder = Base64.getEncoder()
    }

    init {
        Twilio.init(twilioConfiguration.sid, twilioConfiguration.token)
    }

    val appId = appService.getApp(CodexConstants.APP_NAME).id
    val typesByFqn = CodexConstants.AppType.values().associate { it to appService.getAppType(it.fqn) }
    val appConfigs: IMap<AppConfigKey, AppTypeSetting> = HazelcastMap.APP_CONFIGS.getMap(hazelcast)

    val codexMedia: IMap<UUID, Base64Media> = HazelcastMap.CODEX_MEDIA.getMap(hazelcast)
    val propertyTypesByAppType = typesByFqn.values.associate { it.id to edmManager.getPropertyTypesOfEntityType(it.entityTypeId) }

    val propertyTypesByFqn = propertyTypesByAppType.values.flatMap { it.values }.associate { it.type to it.id }

    val textingExecutor = Executors.newSingleThreadExecutor()
    val feedsExecutor = Executors.newSingleThreadExecutor()

    val twilioQueue = HazelcastQueue.TWILIO.getQueue(hazelcast)
    val feedsQueue = HazelcastQueue.TWILIO_FEED.getQueue(hazelcast)

    val textingExecutorWorker = textingExecutor.execute {
        Stream.generate { twilioQueue.take() }.forEach { (organizationId, messageEntitySetId, messageContents, toPhoneNumber, senderId, attachment) ->

            try {
                //Not very efficient.
                val phone = organizations.getOrganization(organizationId)!!.smsEntitySetInfo
                        .flatMap { (phoneNumber, _, entitySetIds, _) -> entitySetIds.map { it to phoneNumber } }
                        .toMap()
                        .getValue(messageEntitySetId)

                if (phone == "") {
                    throw BadRequestException("No source phone number set for organization!")
                }


                val callbackPath = "${twilioConfiguration.callbackBaseUrl}${CodexApi.BASE}${CodexApi.INCOMING}/$organizationId${CodexApi.STATUS}"

                val messageCreator = Message
                        .creator(PhoneNumber(toPhoneNumber), PhoneNumber(phone), messageContents)
                        .setStatusCallback(URI.create(callbackPath))

                if (attachment != null) {
                    messageCreator.setMediaUrl(writeMediaAndGetPath(attachment))
                }

                val message = messageCreator.create()
                processOutgoingMessage(message, organizationId, senderId, attachment)

            } catch (e: Exception) {
                logger.error(
                        "Unable to send outgoing message to phone number $toPhoneNumber in entity set $messageEntitySetId for organization $organizationId",
                        e
                )
            }
        }
    }

    val fromPhone = PhoneNumber(twilioConfiguration.shortCode)
    val feedsExecutorWorker = feedsExecutor.execute {
        Stream.generate { feedsQueue.take() }.forEach { (messageContents, toPhoneNumber) ->
            try {
                Message.creator(PhoneNumber(toPhoneNumber), fromPhone, messageContents).create()
            } catch (e: Exception) {
                logger.error("Unable to send outgoing feed update message to phone number $toPhoneNumber", e)
            }
        }
    }

    fun writeMediaAndGetPath(base64Media: Base64Media): String {
        var id = UUID.randomUUID()
        while (codexMedia.putIfAbsent(id, base64Media) != null) {
            id = UUID.randomUUID()
        }

        return "${twilioConfiguration.callbackBaseUrl}/datastore/codex/media/$id"
    }

    fun getAndDeleteMedia(id: UUID): Base64Media {
        val media = codexMedia.getValue(id)
        codexMedia.delete(id)
        return media
    }

    fun processOutgoingMessage(message: Message, organizationId: UUID, senderId: String, attatchment: Base64Media?) {
        val dateTime = formatDateTime(message.dateCreated)
        val phoneNumber = message.to
        val messageId = message.sid
        val text = message.body

        val sender = principalsManager.getUser(senderId)
        val attachments = attatchment?.let {
            setOf(mapOf(
                    "content-type" to it.contentType,
                    "data" to it.data
            ))
        } ?: emptySet()

        /* create entities */

        val contactEDK = getContactEntityDataKey(organizationId, phoneNumber)

        val messageEDK = getMessageEntityDataKey(organizationId, dateTime, messageId, text, isOutgoing = true, media = attachments)


        /* create associations */

        val associations: ListMultimap<UUID, DataEdge> = ArrayListMultimap.create()

        val sentToEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.SENT_TO)
        val sentFromEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.SENT_FROM)
        val senderEDK = getSenderEntityDataKey(organizationId, sender)

        val associationEntity = mapOf(getPropertyTypeId(CodexConstants.PropertyType.DATE_TIME) to setOf(dateTime))

        associations.put(sentToEntitySetId, DataEdge(messageEDK, contactEDK, associationEntity))
        associations.put(sentFromEntitySetId, DataEdge(messageEDK, senderEDK, associationEntity))

        dataGraphManager.createAssociations(
                associations, mapOf(
                sentToEntitySetId to getPropertyTypes(CodexConstants.AppType.SENT_TO),
                sentFromEntitySetId to getPropertyTypes(CodexConstants.AppType.SENT_FROM)
        )
        )
    }

    fun processIncomingMessage(organizationId: UUID, request: HttpServletRequest) {

        val messageId = request.getParameter(CodexConstants.Request.SID.parameter)
        val phoneNumber = request.getParameter(CodexConstants.Request.FROM.parameter)
        val text = request.getParameter(CodexConstants.Request.BODY.parameter)
        val dateTime = OffsetDateTime.now()
        val numMedia = request.getParameter(CodexConstants.Request.NUM_MEDIA.parameter).toInt()

        val finalMedia = Sets.newLinkedHashSetWithExpectedSize<Map<String, String>>(numMedia)

        if (numMedia > 0) {
            val maybeImages = Lists.newArrayListWithExpectedSize<Pair<String, ListenableFuture<String>>>(numMedia)
            for (i in 0 until numMedia) {
                val mediaUrl = request.getParameter("${CodexConstants.Request.MEDIA_URL_PREFIX.parameter}$i")
                val mediaType = request.getParameter("${CodexConstants.Request.MEDIA_TYPE_PREFIX.parameter}$i")
                maybeImages.add(mediaType to retrieveMediaAsBaseSixtyFour(mediaUrl))
            }
            maybeImages.forEach {
                finalMedia.add(mapOf("content-type" to it.first, "data" to it.second.get()))
            }
        }

        /* create entities */

        val contactEDK = getContactEntityDataKey(organizationId, phoneNumber)
        val messageEDK = getMessageEntityDataKey(
                organizationId, dateTime, messageId, text, isOutgoing = false, media = finalMedia
        )

        /* create associations */

        val associations: ListMultimap<UUID, DataEdge> = ArrayListMultimap.create()

        val edgeEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.SENT_FROM)

        associations.put(
                edgeEntitySetId, DataEdge(
                messageEDK, contactEDK,
                mapOf(getPropertyTypeId(CodexConstants.PropertyType.DATE_TIME) to setOf(dateTime))
        )
        )

        dataGraphManager.createAssociations(
                associations, mapOf(edgeEntitySetId to getPropertyTypes(CodexConstants.AppType.SENT_FROM))
        )
    }

    fun retrieveMediaAsBaseSixtyFour(mediaUrl: String): ListenableFuture<String> {
        return executor.submit(Callable {
            encoder.encodeToString(URL(mediaUrl).readBytes())
        })
    }

    fun updateMessageStatus(organizationId: UUID, messageId: String, status: Message.Status) {

        val wasDelivered = when (status) {
            Message.Status.DELIVERED -> true
            Message.Status.UNDELIVERED -> false
            Message.Status.FAILED -> false
            else -> return
        }

        val messageEntitySetId = getEntitySetId(organizationId, CodexConstants.AppType.MESSAGES)
        val messageEntityKeyId = entityKeyIdService.getEntityKeyId(messageEntitySetId, messageId)

        dataGraphManager.partialReplaceEntities(
                messageEntitySetId,
                mapOf(
                        messageEntityKeyId to mutableMapOf(
                                getPropertyTypeId(CodexConstants.PropertyType.WAS_DELIVERED) to mutableSetOf(
                                        wasDelivered
                                )
                        )
                ),
                getPropertyTypes(CodexConstants.AppType.MESSAGES)
        )
    }

    private fun getContactEntityDataKey(organizationId: UUID, phoneNumber: String): EntityDataKey {
        val entitySetId = getEntitySetId(organizationId, CodexConstants.AppType.CONTACT_INFO)
        val entityKeyId = entityKeyIdService.getEntityKeyId(entitySetId, phoneNumber)

        val entity = mutableMapOf(
                getPropertyTypeId(CodexConstants.PropertyType.PHONE_NUMBER) to mutableSetOf<Any>(phoneNumber)
        )

        dataGraphManager.mergeEntities(
                entitySetId,
                mapOf(entityKeyId to entity),
                getPropertyTypes(CodexConstants.AppType.CONTACT_INFO)
        )

        return EntityDataKey(entitySetId, entityKeyId)
    }

    private fun getMessageEntityDataKey(
            organizationId: UUID,
            dateTime: OffsetDateTime,
            messageId: String,
            text: String,
            isOutgoing: Boolean,
            media: Set<Map<String, String>>
    ): EntityDataKey {

        val entity = mutableMapOf(
                getPropertyTypeId(CodexConstants.PropertyType.ID) to mutableSetOf<Any>(messageId),
                getPropertyTypeId(CodexConstants.PropertyType.DATE_TIME) to mutableSetOf<Any>(dateTime),
                getPropertyTypeId(CodexConstants.PropertyType.TEXT) to mutableSetOf<Any>(text),
                getPropertyTypeId(CodexConstants.PropertyType.IS_OUTGOING) to mutableSetOf<Any>(isOutgoing),
                getPropertyTypeId(CodexConstants.PropertyType.IMAGE_DATA) to media.toMutableSet<Any>()
        )
        val entitySetId = getEntitySetId(organizationId, CodexConstants.AppType.MESSAGES)
        val entityKeyId = entityKeyIdService.getEntityKeyId(entitySetId, messageId)

        dataGraphManager.mergeEntities(
                entitySetId,
                mapOf(entityKeyId to entity),
                getPropertyTypes(CodexConstants.AppType.MESSAGES)
        )

        return EntityDataKey(entitySetId, entityKeyId)
    }

    private fun getSenderEntityDataKey(organizationId: UUID, user: User): EntityDataKey {

        val entity = mutableMapOf(
                getPropertyTypeId(CodexConstants.PropertyType.PERSON_ID) to mutableSetOf<Any>(user.id),
                getPropertyTypeId(CodexConstants.PropertyType.NICKNAME) to mutableSetOf<Any>(user.email)
        )
        val entitySetId = getEntitySetId(organizationId, CodexConstants.AppType.PEOPLE)
        val entityKeyId = entityKeyIdService.getEntityKeyId(entitySetId, user.id)

        dataGraphManager.mergeEntities(
                entitySetId, mapOf(entityKeyId to entity), getPropertyTypes(CodexConstants.AppType.PEOPLE)
        )

        return EntityDataKey(entitySetId, entityKeyId)
    }

    private fun getEntitySetId(organizationId: UUID, type: CodexConstants.AppType): UUID {
        val appTypeId = typesByFqn.getValue(type).id
        val ack = AppConfigKey(appId, organizationId, appTypeId)
        return appConfigs[ack]!!.entitySetId
    }

    private fun getPropertyTypes(type: CodexConstants.AppType): Map<UUID, PropertyType> {
        val appTypeId = typesByFqn.getValue(type).id
        return propertyTypesByAppType.getValue(appTypeId)
    }

    private fun formatDateTime(dateTime: DateTime): OffsetDateTime {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(dateTime.toInstant().millis), UTC)
    }

    private fun getPropertyTypeId(property: CodexConstants.PropertyType): UUID {
        return propertyTypesByFqn.getValue(property.fqn)
    }

}