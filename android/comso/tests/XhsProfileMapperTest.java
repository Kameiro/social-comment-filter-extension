package dev.local.commentcollector;

import org.json.JSONObject;

public class XhsProfileMapperTest {
    public static void main(String[] args) throws Exception {
        testGenderParsing();
        testCleanBio();
        testExtractBio();
        testBuildProfile();
        System.out.println("PASS: XhsProfileMapperTest passed all assertions");
    }

    private static void testGenderParsing() {
        // Integer cases
        assert "male".equals(XhsProfileMapper.parseGender(0)) : "0 should be male";
        assert "female".equals(XhsProfileMapper.parseGender(1)) : "1 should be female";
        assert "unknown".equals(XhsProfileMapper.parseGender(2)) : "2 should be unknown";
        assert "unknown".equals(XhsProfileMapper.parseGender(3)) : "3 should be unknown";
        assert "unknown".equals(XhsProfileMapper.parseGender(-1)) : "-1 should be unknown";

        // String cases
        assert "male".equals(XhsProfileMapper.parseGender("0")) : "'0' should be male";
        assert "female".equals(XhsProfileMapper.parseGender("1")) : "'1' should be female";
        assert "unknown".equals(XhsProfileMapper.parseGender("2")) : "'2' should be unknown";
        assert "male".equals(XhsProfileMapper.parseGender("male")) : "'male' should be male";
        assert "female".equals(XhsProfileMapper.parseGender("female")) : "'female' should be female";
        assert "male".equals(XhsProfileMapper.parseGender("男")) : "'男' should be male";
        assert "female".equals(XhsProfileMapper.parseGender("女")) : "'女' should be female";
        assert "unknown".equals(XhsProfileMapper.parseGender(null)) : "null should be unknown";
        assert "unknown".equals(XhsProfileMapper.parseGender("")) : "empty should be unknown";
    }

    private static void testCleanBio() {
        assert "".equals(XhsProfileMapper.cleanBio(null));
        assert "".equals(XhsProfileMapper.cleanBio(""));
        assert "".equals(XhsProfileMapper.cleanBio("  "));
        assert "".equals(XhsProfileMapper.cleanBio("null"));

        // Regular clean bio
        assert "喜欢旅行与美食".equals(XhsProfileMapper.cleanBio("喜欢旅行与美食"));

        // UserDescInfo with actual bio
        String raw1 = "UserDescInfo(desc=养成型博主\n通过互联网挑战不一样的自己\n学舞蹈/学穿搭/学美妆/学剪辑, descHidden=, descTranslation=, descTranslationHidden=, descWithPlaceholder=养成型博主\n通过互联网挑战不一样的自己\n学舞蹈/学穿搭/学美妆/学剪辑, descKeywordsSwitch=true, descKeywords=[], descAtUsers=[], button=null, descPreEdit=null, translateApiParam=null, guidePopup=null)";
        assert "养成型博主\n通过互联网挑战不一样的自己\n学舞蹈/学穿搭/学美妆/学剪辑".equals(XhsProfileMapper.cleanBio(raw1)) : "raw1 failed: " + XhsProfileMapper.cleanBio(raw1);

        // UserDescInfo with empty bio
        String raw2 = "UserDescInfo(desc=, descHidden=, descTranslation=, descTranslationHidden=, descWithPlaceholder=, descKeywordsSwitch=true, descKeywords=[], descAtUsers=[], button=null, descPreEdit=null, translateApiParam=null, guidePopup=null)";
        assert "".equals(XhsProfileMapper.cleanBio(raw2)) : "raw2 should be empty: " + XhsProfileMapper.cleanBio(raw2);

        // UserDescInfo with nested button code
        String raw3 = "UserDescInfo(desc=鞠婧祎全肯定.\n(๑╹ヮ╹๑)ﾉ, descHidden=, descTranslation=, descTranslationHidden=, descWithPlaceholder=鞠婧祎全肯定.\n(๑╹ヮ╹๑)ﾉ, descKeywordsSwitch=true, descKeywords=[], descAtUsers=[], button=TranslateButton(originalButton=TranslateButtonItem(strategy=2, content=翻译), replaceButton=TranslateButtonItem(strategy=3, content=查看原文)), descPreEdit=null, translateApiParam=TranslateApiParam(bizType=user_profile, bizId=user_profile_60fb69f000000000010034e0, textInputs=[TextInput(key=desc, content=鞠婧祎全肯定.\n(๑╹ヮ╹๑)ﾉ)]), guidePopup=null)";
        assert "鞠婧祎全肯定.\n(๑╹ヮ╹๑)ﾉ".equals(XhsProfileMapper.cleanBio(raw3)) : "raw3 failed: " + XhsProfileMapper.cleanBio(raw3);

        // UserDescInfo with commas in the bio text
        String raw4 = "UserDescInfo(desc=生活已经够累了，如果爱不能给你力量，又不能给你提供情绪价值，也没有陪伴，更没有物质，还要消耗你的情绪，那爱的意义是什么？相逢的意义在于彼此照亮，不然的话，一个人淋雨也浪漫，一个人吹风也清醒。, descHidden=, descTranslation=, descTranslationHidden=, descWithPlaceholder=生活已经够累了，如果爱不能给你力量，又不能给你提供情绪价值，也没有陪伴，更没有物质，还要消耗你的情绪，那爱的意义是什么？相逢的意义在于彼此照亮，不然的话，一个人淋雨也浪漫，一个人吹风也清醒。, descKeywordsSwitch=true, descKeywords=[], descAtUsers=[], button=null, descPreEdit=null, translateApiParam=null, guidePopup=null)";
        assert "生活已经够累了，如果爱不能给你力量，又不能给你提供情绪价值，也没有陪伴，更没有物质，还要消耗你的情绪，那爱的意义是什么？相逢的意义在于彼此照亮，不然的话，一个人淋雨也浪漫，一个人吹风也清醒。".equals(XhsProfileMapper.cleanBio(raw4)) : "raw4 failed: " + XhsProfileMapper.cleanBio(raw4);
    }

    private static void testExtractBio() {
        // Direct field
        class MockUserInfoField {
            public final String desc = "直接字段简介";
        }
        assert "直接字段简介".equals(XhsProfileMapper.extractBio(new MockUserInfoField()));

        // UserDescInfo object
        class MockDescInfo {
            public String getDesc() { return "嵌套对象简介"; }
        }
        class MockUserInfoGetter {
            public MockDescInfo getUserDescInfo() { return new MockDescInfo(); }
        }
        assert "嵌套对象简介".equals(XhsProfileMapper.extractBio(new MockUserInfoGetter()));
    }

    private static void testBuildProfile() throws Exception {
        class MockDescInfo {
            public String getDesc() { return "博主日常分享"; }
        }
        class MockUserInfo {
            public final int gender = 1; // Female in XHS
            public final String nickname = "红薯小姐";
            public final String ipLocation = "IP 属地：浙江";
            public final String redId = "12345678";
            public final String location = "杭州";
            public final int age = 22;
            public MockDescInfo getUserDescInfo() { return new MockDescInfo(); }
        }

        JSONObject prof = XhsProfileMapper.buildProfile(new MockUserInfo(), "u1001");
        assert prof != null;
        assert "xhs".equals(prof.getString("platform"));
        assert "u1001".equals(prof.getString("userId"));
        assert "female".equals(prof.getString("gender")) : "gender should be female, got: " + prof.getString("gender");
        assert "红薯小姐".equals(prof.getString("nickname"));
        assert "浙江".equals(prof.getString("profileIpRegion")) : "profileIpRegion failed: " + prof.getString("profileIpRegion");
        assert "博主日常分享".equals(prof.getString("bio")) : "bio failed: " + prof.getString("bio");
        assert "12345678".equals(prof.getString("redId"));
        assert "杭州".equals(prof.getString("location"));
        assert prof.getInt("age") == 22;
    }
}
