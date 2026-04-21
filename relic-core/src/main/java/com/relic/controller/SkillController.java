package com.relic.controller;

import com.relic.service.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/skills")
public class SkillController {

    @Autowired
    private SkillService skillService;

    @GetMapping
    public Map<String, Object> listSkills() {
        return skillService.listSkills();
    }

    @PostMapping("/{skillKey}/enabled")
    public Map<String, Object> setSkillEnabled(@PathVariable("skillKey") String skillKey,
                                               @RequestBody Map<String, Object> request) {
        Object enabledObj = request.get("enabled");
        boolean enabled = enabledObj instanceof Boolean b
                ? b
                : Boolean.parseBoolean(String.valueOf(enabledObj));
        return skillService.setSkillEnabled(skillKey, enabled);
    }

    @PostMapping("/import")
    public Map<String, Object> importSkill(@RequestBody Map<String, String> request) {
        String source = request.getOrDefault("source", "");
        return skillService.importSkill(source);
    }
}
