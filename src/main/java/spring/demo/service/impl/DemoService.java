package spring.demo.service.impl;

import spring.demo.service.IDemoService;
import spring.framework.annotation.Service;

@Service
public class DemoService implements IDemoService {
    @Override
    public String get( String name ) {
        return "This is " + name;
    }
}
