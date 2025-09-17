const functions = require('firebase-functions');
const admin = require('firebase-admin');

try { admin.app(); } catch { admin.initializeApp(); }

// -- Helpers ---------------------------------------------------------------

/** Divide un array en chunks de tamaño n (FCM permite máx. 500 tokens por lote). */
function chunk(arr, n = 500) {
    const out = [];
    for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
    return out;
}

/** Lee participantIds del room. Si no existe, intenta deducirlos del roomId o del mensaje. */
async function getParticipants(roomId, msg) {
    const ref = admin.firestore().collection('rooms').doc(roomId);
    const snap = await ref.get();
    const data = snap.data() || {};
    let participants = Array.isArray(data.participantIds) ? data.participantIds : [];

    // Fallback 1: roomId con formato "uidA_uidB" (1:1)
    if (!participants.length && typeof roomId === 'string' && roomId.includes('_')) {
        participants = roomId.split('_').filter(Boolean);
    }

    // Fallback 2: si el mensaje trae receiverId/s (tu estructura podría traerlo)
    if (!participants.length && msg?.receiverId) {
        participants = [msg.senderId, msg.receiverId].filter(Boolean);
    }

    return participants;
}

/** Dado un conjunto de UIDs, devuelve tokens únicos (fcmTokens[] y fcmToken). */
async function collectTokens(uids = []) {
    const userSnaps = await Promise.all(
        uids.map((uid) => admin.firestore().collection('users').doc(uid).get())
    );

    let tokens = [];
    for (const s of userSnaps) {
        const d = s.data() || {};
        if (Array.isArray(d.fcmTokens)) tokens = tokens.concat(d.fcmTokens);
        if (typeof d.fcmToken === 'string' && d.fcmToken) tokens.push(d.fcmToken);
    }
    return [...new Set(tokens)].filter(Boolean);
}

/** Limpia tokens inválidos de todos los destinatarios. */
async function removeInvalidTokensFromUsers(uids, invalidTokens) {
    if (!invalidTokens.length || !uids.length) return;
    const batchSize = 400; // por si hay muchos destinatarios
    const chunks = chunk(uids, batchSize);
    await Promise.all(chunks.map(async (uidChunk) => {
        const ops = uidChunk.map((uid) =>
            admin.firestore().collection('users').doc(uid)
                .update({ fcmTokens: admin.firestore.FieldValue.arrayRemove(...invalidTokens) })
                .catch(() => null)
        );
        await Promise.all(ops);
    }));
}

// -- PUSH: nuevo mensaje ---------------------------------------------------

exports.sendMessageNotification = functions.firestore
    .document('rooms/{roomId}/messages/{messageId}')
    .onCreate(async (snap, context) => {
        const msg = snap.data() || {};
        const roomId = context.params.roomId;
        const messageId = context.params.messageId;

        // 1) Participantes y destinatarios (excluye remitente)
        const participants = await getParticipants(roomId, msg);
        const recipients = participants.filter((uid) => uid && uid !== msg.senderId);

        if (!recipients.length) {
            console.log('No recipients', { roomId, participants, senderId: msg.senderId });
            return null;
        }

        // 2) Tokens
        const tokens = await collectTokens(recipients);
        console.log('rooms push', { roomId, recipientsCount: recipients.length, tokenCount: tokens.length });

        if (!tokens.length) return null;

        // 3) Payload
        const title = msg.senderName || 'Nuevo mensaje';
        const body = 'Tienes un mensaje nuevo';

        const common = {
            notification: { title, body },
            data: {
                type: 'chat_message',
                roomId: roomId || '',
                messageId: messageId || '',
                senderId: msg.senderId || '',
                senderName: msg.senderName || '',
                body,
            },
            android: { priority: 'high' },
            apns: { headers: { 'apns-priority': '10' } },
        };

        // 4) Envío en lotes y limpieza de tokens inválidos
        const tokenBatches = chunk(tokens, 500);
        let success = 0, failure = 0;
        let invalidTokens = [];

        for (const batch of tokenBatches) {
            const res = await admin.messaging().sendEachForMulticast({ tokens: batch, ...common });
            success += res.successCount;
            failure += res.failureCount;

            res.responses.forEach((r, i) => {
                const code = r.error?.code;
                if (
                    code === 'messaging/invalid-registration-token' ||
                    code === 'messaging/registration-token-not-registered'
                ) {
                    invalidTokens.push(batch[i]);
                }
            });
        }

        console.log('FCM result', { success, failure, invalid: invalidTokens.length });

        if (invalidTokens.length) {
            invalidTokens = [...new Set(invalidTokens)];
            await removeInvalidTokensFromUsers(recipients, invalidTokens);
        }

        return null;
    });

// -- Unread counts (y metadatos básicos del room) --------------------------

exports.updateUnreadCounts = functions.firestore
    .document('rooms/{roomId}/messages/{messageId}')
    .onCreate(async (snap, context) => {
        const message = snap.data() || {};
        const roomId = context.params.roomId;
        const senderId = message.senderId;

        const roomRef = admin.firestore().collection('rooms').doc(roomId);
        const roomSnap = await roomRef.get();
        const participants = (roomSnap.get('participantIds') || []).filter(Boolean);

        const updates = {};

        // Incrementa unread para todos los que no son el remitente
        participants.forEach((id) => {
            if (id !== senderId) {
                updates[`unreadCounts.${id}`] = admin.firestore.FieldValue.increment(1);
            }
        });

        // (Opcional útil): actualiza metadatos del room para tu lista
        updates['updatedAt'] = admin.firestore.FieldValue.serverTimestamp();
        updates['lastSenderId'] = senderId || '';

        if (Object.keys(updates).length > 0) {
            return roomRef.set(updates, { merge: true });
        }
        return null;
    });
