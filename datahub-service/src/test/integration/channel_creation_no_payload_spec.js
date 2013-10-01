require('./integration_config.js');

var testName = "channel_creation_no_payload_spec";
utils.configureFrisby();

frisby.create(testName + ':Test create channel with empty name')
	.post(channelUrl, null, { body: '' })
	.addHeader("Content-Type", "application/json")
	.expectStatus(400)
	.toss();



