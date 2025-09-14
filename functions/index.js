const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

exports.sendMessageNotification = functions.firestore
  .document('rooms/{roomId}/messages/{messageId}')
  .onCreate(async (snap, context) => {
    const message = snap.data();
    const roomId = context.params.roomId || '';
    const participants = roomId.split('_');
    const recipientId = participants.find(id => id !== message.senderId);
    if (!recipientId) {
      return null;
    }

    const userDoc = await admin.firestore().collection('users').doc(recipientId).get();
    const tokens = userDoc.data()?.fcmTokens || [];
    if (tokens.length === 0) {
      return null;
    }

    return admin.messaging().sendEachForMulticast({
      tokens,
      notification: {
        title: message.senderName || 'Nuevo mensaje',
        body: message.text || '',
      },
      android: { priority: 'high' },
    });
  });

exports.updateUnreadCounts = functions.firestore
  .document('rooms/{roomId}/messages/{messageId}')
  .onCreate(async (snap, context) => {
    const message = snap.data();
    const roomId = context.params.roomId;
    const senderId = message.senderId;
    const roomRef = admin.firestore().collection('rooms').doc(roomId);
    const roomSnap = await roomRef.get();
    const participants = roomSnap.get('participantIds') || [];
    const updates = {};
    participants.forEach(id => {
      if (id !== senderId) {
        updates[`unreadCounts.${id}`] = admin.firestore.FieldValue.increment(1);
      }
    });
    if (Object.keys(updates).length > 0) {
      return roomRef.update(updates);
    }
    return null;
  });
