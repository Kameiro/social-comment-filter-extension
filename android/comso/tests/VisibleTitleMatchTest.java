package dev.local.commentcollector;

public final class VisibleTitleMatchTest {
    public static void main(String[] args) {
        assert VisibleTitleMatch.matches("【我们是冠军！】\n风起今朝，冠军是BLG！更多内容", "【我们是冠军！】\n风起今朝，冠军是BLG！");
        assert !VisibleTitleMatch.matches("【我们是冠军！】旧作品不同内容", "【我们是冠军！】\n风起今朝，冠军是BLG！");
        assert !VisibleTitleMatch.matches("冠军", "冠军");
        assert !VisibleTitleMatch.matches("其他前缀风起今朝冠军是BLG", "风起今朝冠军是BLG");
        System.out.println("Visible title match passed");
    }
}
