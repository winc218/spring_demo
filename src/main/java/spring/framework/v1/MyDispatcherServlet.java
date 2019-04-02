package spring.framework.v1;

import spring.framework.annotation.Autowired;
import spring.framework.annotation.Controller;
import spring.framework.annotation.RequestMapping;
import spring.framework.annotation.Service;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 简易版Spring框架
 */
public class MyDispatcherServlet extends HttpServlet {
    private Map< String, Object > mapping = new HashMap<>();
    private Map< String, Object > hanlderMapping = new HashMap<>();

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        doPost( req, resp );
    }

    @Override
    protected void doPost( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        try {
            doDispatch( req, resp );
        } catch ( InvocationTargetException | IllegalAccessException e ) {
            e.printStackTrace();
        }
    }

    private void doDispatch( HttpServletRequest req, HttpServletResponse resp ) throws IOException, InvocationTargetException, IllegalAccessException {
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace( contextPath, "" ).replaceAll( "/+", "/" );
        if ( !hanlderMapping.containsKey( url ) ) {
            resp.getWriter().write( "404 Not Found!!!" );
            return;
        }
        Method method = ( Method ) this.hanlderMapping.get( url );
        Map< String, String[] > params = req.getParameterMap();

        method.invoke( this.mapping.get( method.getDeclaringClass().getName() ), new Object[] { req, resp, params.get( "name" )[ 0 ] } );
    }

    @Override
    public void init( ServletConfig config ) {
        //1.通过web.xml中配置的配置文件信息，查找application.properties
        Properties configContext = new Properties();
        try ( InputStream is = this.getClass().getClassLoader().getResourceAsStream( config.getInitParameter( "contextConfigLocation" ) ) ) {
            // 2.载入application.properties
            configContext.load( is );
            // 3.扫描配置文件中指定的包的文件
            final String scanPackage = configContext.getProperty( "scanPackage" );
            // 扫描类文件
            doScaner( scanPackage );

            // 4.分别取出所有扫描到的类进行注解识别
            for ( String className : mapping.keySet() ) {
                if ( !className.contains( "." ) ) {
                    continue;
                }
                try {
                    Class< ? > clazz = Class.forName( className );
                    // 处理所有Controller类
                    if ( clazz.isAnnotationPresent( Controller.class ) ) {
                        // 把满足类型为Controller的类进行实例化，并放入到mapping中
                        mapping.put( className, clazz.newInstance() );
                        String baseUrl = "";
                        // 从Controller的RequestMapping注解中取得URL，作为根URL
                        if ( clazz.isAnnotationPresent( RequestMapping.class ) ) {
                            final RequestMapping requestMapping = clazz.getAnnotation( RequestMapping.class );
                            baseUrl = requestMapping.value();
                        }
                        // 取得Controller中所有带有注解RequestMapping的方法
                        // 并取得他们的URL，与baseUrl组装成完整的访问路径
                        final Method[] methods = clazz.getMethods();
                        for ( Method method : methods ) {
                            if ( !method.isAnnotationPresent( RequestMapping.class ) ) {
                                continue;
                            }
                            final RequestMapping requestMapping = method.getAnnotation( RequestMapping.class );
                            final String subUrl = requestMapping.value();
                            String url = ( baseUrl + "/" + subUrl ).replaceAll( "/+", "/" );
                            // 把URL和对应的方法（反射出来的对象）放入到专门的HandlerMapping中
                            // 用户请求时实际上就是通过URL去找出对应的方法执行
                            hanlderMapping.put( url, method );
                        }
                    } else if ( clazz.isAnnotationPresent( Service.class ) ) {
                        // 把注解为Service的类进行初始化并放入mapping中
                        // 这里隐藏了bug，如果用户自定义了名字，将会put到mapping中，在循环中操作map，会抛出异常
                        String beanName = clazz.getAnnotation( Service.class ).value();
                        // 如果Service没有自定义名字，就采用完整类名
                        if ( "".equals( beanName ) ) {
                            beanName = clazz.getName();
                        }
                        final Object instance = clazz.newInstance();
                        mapping.put( beanName, instance );
                        // 如果该类继承了多个接口，把这些接口的对应的实例都指向该对象
                        for ( Class< ? > i : clazz.getInterfaces() ) {
                            mapping.put( i.getName(), instance );
                        }
                    }
                } catch ( ClassNotFoundException | IllegalAccessException | InstantiationException e ) {
                    e.printStackTrace();
                }
            }
        } catch ( IOException e ) {
            e.printStackTrace();
        }

        // 为什么要先循环一遍mapping，再单独循环一遍
        // 因为可能在设置Autowried的时候，可能对应的对象还没有被初始化，导致报错。
        for ( Object object : mapping.values() ) {
            final Class< ? > clazz = object.getClass();
            // 得到该类中所有成员
            final Field[] fields = clazz.getDeclaredFields();
            for ( Field field : fields ) {
                // 处理所有带有Autowired的成员变量，完成自动注入
                if ( field.isAnnotationPresent( Autowired.class ) ) {
                    Autowired autowired = field.getAnnotation( Autowired.class );
                    // 从注解中取出用户自定义的成员变量的别名
                    String filedName = autowired.value();
                    if ( "".equals( filedName ) ) {
                        // 如果没有取到用户自定义的变量名，则设置为变量的类型
                        filedName = field.getType().getName();
                    }
                    // 处理私有成员变量
                    field.setAccessible( true );
                    try {
                        // 在mapping中找到对应实例，注入到该成员变量中
                        field.set( mapping.get( clazz.getName() ), mapping.get( filedName ) );
                    } catch ( IllegalAccessException e ) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 扫描配置文件中指定的包路径下的类文件
     * @param scanPackage
     */
    private void doScaner( final String scanPackage ) {
        final URL resource = this.getClass().getClassLoader().getResource( "/" + scanPackage.replaceAll( "\\.", "/" ) );
        try {
            final Path packageDir = Paths.get( resource.toURI() );
            Files.newDirectoryStream( packageDir ).forEach( path -> {
                if ( path.toFile().isDirectory() ) {
                    doScaner( scanPackage + "." + path.getFileName() );
                } else {
                    // 存入的类名类似：spring.demo.mvc.DemoAction
                    String clazzName = ( scanPackage + "." + path.getFileName() ).replace( ".class", "" );
                    mapping.put( clazzName, null );
                }
            } );
        } catch ( URISyntaxException | IOException e ) {
            e.printStackTrace();
        }
    }
}
