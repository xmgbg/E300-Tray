package com.ezhan.amr.data.datatype;

public class VoicePrompt {
    private String function;
    private String content;

    public VoicePrompt() {
    }

    public VoicePrompt(String function, String content) {
        this.function = function;
        this.content = content;
    }

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "VoicePrompt{" +
                "function='" + function + '\'' +
                ", content='" + content + '\'' +
                '}';
    }
}
