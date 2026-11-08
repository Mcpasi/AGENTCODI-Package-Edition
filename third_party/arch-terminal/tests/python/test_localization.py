import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


class LocalizationTests(unittest.TestCase):
    def test_both_languages_cover_all_messages_and_format_arguments(self):
        resources = Path(__file__).resolve().parents[2] / "app/src/main/res"
        german = ET.parse(resources / "values/strings.xml").getroot()
        english = ET.parse(resources / "values-en/strings.xml").getroot()

        def messages(root):
            result = {}
            for item in root:
                if item.tag == "plurals":
                    for quantity in item:
                        result[(item.attrib["name"], quantity.attrib["quantity"])] = quantity.text
                else:
                    result[(item.attrib["name"], "string")] = item.text
            return result

        original, translated = messages(german), messages(english)
        self.assertEqual(original.keys(), translated.keys())
        for key, value in original.items():
            with self.subTest(message=key):
                self.assertTrue(value.strip())
                self.assertTrue(translated[key].strip())
                placeholders = r"%(?:(\d+)\$)?(?:\.\d+)?([sdf])"
                self.assertEqual(re.findall(placeholders, value), re.findall(placeholders, translated[key]))


if __name__ == "__main__":
    unittest.main()
