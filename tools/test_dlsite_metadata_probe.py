import unittest

from dlsite_metadata_probe import RobotsPolicy, indexes, normalize_name, parse_credits


class MetadataProbeTests(unittest.TestCase):
    def test_roles_are_explicit_and_multiple_actors_are_preserved(self):
        data = parse_credits('''<table>
            <tr><th>サークル名</th><td><a href="/maniax/circle/profile/=/maker_id/RG00001.html">Test Circle</a></td></tr>
            <tr><th>作者</th><td><a href="/author">Writer</a></td></tr>
            <tr><th>声優</th><td><a href="/maniax/fsr/=/keyword_creater/a">Actor A</a> /
              <a href="/maniax/fsr/=/keyword_creater/b"><span>Actor B</span></a></td></tr>
            </table>''', "RJ000001", "https://www.dlsite.com/maniax/work/=/product_id/RJ000001.html")
        self.assertEqual("RG00001", data["circles"][0]["id"])
        self.assertEqual(["Actor A", "Actor B"], [v["credited_name"] for v in data["voice_credits"]])
        self.assertTrue(all(v["identity_status"] == "credited_name_only" for v in data["voice_credits"]))

    def test_unexpected_page_is_not_a_successful_empty_record(self):
        with self.assertRaises(ValueError):
            parse_credits("<html>please verify</html>", "RJ000001", "https://www.dlsite.com/")

    def test_normalization_is_only_a_candidate_key(self):
        self.assertEqual("Actor A", normalize_name("Ａｃｔｏｒ　Ａ"))
        record = {"rj": "RJ1", "circles": [], "voice_credits": [{"lookup_key": "Actor A", "credited_name": "Ａｃｔｏｒ Ａ"}]}
        index = indexes([record])["voices_by_name_candidate"][0]
        self.assertEqual(["Ａｃｔｏｒ Ａ"], index["credited_names"])
        self.assertEqual("name_lookup_candidate_not_person_id", index["identity_status"])

    def test_robots_wildcards_longest_allow_and_specific_agents(self):
        text = '''User-agent: blocked-bot
Disallow: /
User-agent: *
Disallow: /*search/result
Disallow: /*/author/list/
Disallow: /*/fsr/=/*/per_page/*/page/
Allow: /*/fsr/=/*/per_page/*/page/1/
Crawl-delay: 10
'''
        policy = RobotsPolicy(text, "YsPlayerMetadataResearch/0.1")
        self.assertTrue(policy.allows("https://www.dlsite.com/maniax/work/=/product_id/RJ1.html"))
        self.assertFalse(policy.allows("https://www.dlsite.com/maniax/search/result"))
        self.assertFalse(policy.allows("https://www.dlsite.com/maniax/author/list/"))
        self.assertTrue(policy.allows("https://www.dlsite.com/maniax/fsr/=/x/per_page/30/page/1/"))
        self.assertFalse(policy.allows("https://www.dlsite.com/maniax/fsr/=/x/per_page/30/page/2/"))
        self.assertFalse(RobotsPolicy(text, "blocked-bot").allows("https://www.dlsite.com/anything"))
        self.assertEqual(10, policy.delay)


if __name__ == "__main__":
    unittest.main()
