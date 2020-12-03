# troubleflippper

Prerequisites:
- Enable 'Subscription Manager' on the client-username 'solace-cloud-client'
- Update credentials in client/src/App.vue
- Set following properties in server/src/main/resources/application.properties
solace.cloud.url=tcps://mr16jp1pl7xwul.messaging.solace.cloud:55443
solace.cloud.username=solace-cloud-client
solace.cloud.password=n46qgpsunr1g38kjlm3glrbnrf
solace.cloud.vpn=my-vpn-name

tournament.playersPerTeam=1 # To be tested, other tournament properties might be required...

server.port=8888 # to not conflict with UI running on 8080
