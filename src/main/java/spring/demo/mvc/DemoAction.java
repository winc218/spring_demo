package spring.demo.mvc;


import spring.demo.service.IDemoService;
import spring.framework.annotation.Autowired;
import spring.framework.annotation.Controller;
import spring.framework.annotation.RequestMapping;
import spring.framework.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
@RequestMapping( "/spring/demo" )
public class DemoAction {
    @Autowired
    private IDemoService demoService;

    @RequestMapping("/query")
    public void query( HttpServletRequest req, HttpServletResponse resp, @RequestParam("name") String name) {
        String result = "My name is " + name;
        try {
            System.out.println( demoService.get( name ) );
            resp.getWriter().write( result );
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }
}
