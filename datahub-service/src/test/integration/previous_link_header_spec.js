require('./integration_config.js');

var channelName = utils.randomChannelName();
var testName = "previous_link_header_spec";

utils.configureFrisby();

utils.runInTestChannel(channelName, function (channelResponse) {
    var channelResource = channelResponse['_links']['self']['href'];
    frisby.create(testName + ': Inserting a first item')
        .post(channelResource, null, { body: "FIRST ITEM"})
        .addHeader("Content-Type", "text/plain")
        .expectStatus(201)
        .afterJSON(function (response) {
            var firstItemUrl = response['_links']['self']['href'];
            frisby.create(testName + ': Verifying that first channel item doesnt have a previous')
                .get(firstItemUrl)
                .expectStatus(200)
                .after(function (err, res, body) {
                    for (var item in res.headers) {
                        if (item == "link") {
                            expect(res.headers[item]).not.toContain("previous");
                        }
                    }
                    frisby.create(testName + ": Inserting a second item")
                        .post(channelResource, null, {body: "SECOND ITEM"})
                        .addHeader("Content-Type", "text/plain")
                        .expectStatus(201)
                        .afterJSON(function (response) {
                            var secondItemUrl = response['_links']['self']['href'];
                            frisby.create(testName + ": Checking the Link header.")
                                .get(secondItemUrl)
                                .expectStatus(200)
                                .expectHeader("link", "<" + firstItemUrl + ">;rel=\"previous\"")
                                .toss()
                        })
                        .toss();
                })
                .toss();
        })
        .toss();
});

