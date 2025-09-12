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
    const token = userDoc.data()?.fcmToken;
    if (!token) {
      return null;
    }

    return admin.messaging().send({
      token,
      notification: {
        title: message.senderName || 'Nuevo mensaje',
        body: message.text || '',
      },
    });
  });
