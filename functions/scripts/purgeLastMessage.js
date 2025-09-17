const admin = require('firebase-admin');

try {
  admin.app();
} catch (error) {
  admin.initializeApp();
}

async function purgeLastMessageFields() {
  const db = admin.firestore();
  const roomsSnapshot = await db.collection('rooms').get();

  if (roomsSnapshot.empty) {
    console.log('No rooms found. Nothing to purge.');
    return;
  }

  let purged = 0;
  const pending = [];

  roomsSnapshot.forEach((doc) => {
    if (doc.get('lastMessage') !== undefined) {
      pending.push(
        doc.ref.update({ lastMessage: admin.firestore.FieldValue.delete() })
      );
      purged += 1;
    }
  });

  while (pending.length) {
    await Promise.all(pending.splice(0, 50));
  }

  console.log(`Purged legacy lastMessage from ${purged} room(s).`);
}

purgeLastMessageFields()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error('Failed to purge legacy lastMessage fields', error);
    process.exit(1);
  });
