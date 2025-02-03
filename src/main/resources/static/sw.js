// TODO simplify

self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('push', event => event.waitUntil(handlePushEvent(event)));

self.addEventListener('notificationclick', event => event.waitUntil(handleNotificationClick(event)));

self.addEventListener('notificationclose', event => console.info('notificationclose event fired'));

async function handlePushEvent(event) {
    console.info('push event received');

    if(!await needToShowNotification()) {
        return;
    }

    const msg = JSON.parse(event.data.json());
    console.info(msg)

    await self.registration.showNotification(msg.title, {
        body: msg.text,
        icon: 'icons/' + msg.icon,
        data: msg
    });
}

async function handleNotificationClick(event) {
    const url = event.notification.data.url

    const allClients = await self.clients.matchAll({ includeUncontrolled: true, type: 'window' });
    const openClient= allClients.find(client => client.url === url)

    if (openClient) {
        await openClient.focus();
    } else {
        await self.clients.openWindow(url);
    }

    event.notification.close();
}

async function needToShowNotification() {
    const allClients = await self.clients.matchAll({ includeUncontrolled: true });

    return allClients.find(client => client.visibilityState === 'visible') === undefined
}
