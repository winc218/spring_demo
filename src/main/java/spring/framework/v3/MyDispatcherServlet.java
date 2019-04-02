package spring.framework.v3;

import spring.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyDispatcherServlet extends HttpServlet {
    private static final String LOCATION = "contextConfigLocation";
    // 通过web.xml中配置的配置文件信息，查找application.properties
    private Properties configContext = new Properties();
    // 存储所有扫描到的类
    private List< String > classNames = new ArrayList< String >();
    // IOC容器，保存所有实例化对象
    // 注册式单例模式
    private Map< String, Object > ioc = new HashMap< String, Object >();

    //保存Contrller中所有Mapping的对应关系
    private List< Handler > handlerMapping = new ArrayList<>();

    @Override
    protected void doGet( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        doPost( req, resp );
    }

    @Override
    protected void doPost( HttpServletRequest req, HttpServletResponse resp ) throws IOException {
        try {
            // 委派模式
            doDispatch( req, resp );
        } catch ( InvocationTargetException | IllegalAccessException e ) {
            e.printStackTrace();
            resp.getWriter().write( "500 Excetion Detail:" + Arrays.toString( e.getStackTrace() ) );
        }
    }

    private void doDispatch( HttpServletRequest req, HttpServletResponse resp ) throws IOException, InvocationTargetException, IllegalAccessException {
        Handler handler = getHandler( req );

        if ( handler == null ) {
            //如果没有匹配上，返回404错误
            resp.getWriter().write( "404 Not Found" );
            return;
        }

        Class< ? >[] parameterTypes = handler.method.getParameterTypes();
        Object[] paramValues = new Object[ parameterTypes.length ];

        // 取得请求中的所有参数
        Map< String, String[] > paramMap = req.getParameterMap();
        paramMap.forEach( ( k, v ) -> {
            String value = Arrays.toString( v ).replaceAll( "\\[|]", "" )
                    .replaceAll( "\\s", "," );
            if ( handler.paramIndexMapping.containsKey( k ) ) {
                Integer index = handler.paramIndexMapping.get( k );
                paramValues[ index ] = convert( parameterTypes[ index ], value );
            }
        } );
        Integer reqIndex = handler.paramIndexMapping.get( HttpServletRequest.class.getName() );
        paramValues[ reqIndex ] = req;
        Integer respIndex = handler.paramIndexMapping.get( HttpServletResponse.class.getName() );
        paramValues[ respIndex ] = resp;

        handler.method.invoke( handler.controller, paramValues );
    }

    private Object convert( Class< ? > type, String value ) {
        if ( Integer.class == type ) {
            return Integer.valueOf( value );
        }
        return value;
    }

    private Handler getHandler( HttpServletRequest req ) {
        if ( handlerMapping.isEmpty() ) {
            return null;
        }
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace( contextPath, "" ).replaceAll( "/+", "/" );

        for ( Handler handler : handlerMapping ) {
            Matcher matcher = handler.pattern.matcher( url );
            //如果没有匹配上继续下一个匹配
            if ( !matcher.matches() ) {
                continue;
            }

            return handler;
        }
        return null;
    }

    @Override
    public void init( ServletConfig config ) {

        //1.加载配置文件
        doLoadConfig( config.getInitParameter( LOCATION ) );
        //2.扫描相关的类
        doScaner( configContext.getProperty( "scanPackage" ) );
        //3.初始化所有相关类的实例，并放入到IOC容器中
        doInstance();
        //4.完成依赖注入
        doAutowired();
        //5.初始化HandlerMapping
        initHanlderMapping();

        System.out.println( "DengJL Spring Framework initial has finished" );
    }

    /**
     * 5.初始化HandlerMapping
     */
    private void initHanlderMapping() {
        if ( ioc.isEmpty() ) {
            return;
        }
        for ( Map.Entry< String, Object > entry : ioc.entrySet() ) {
            Class< ? > clazz = entry.getValue().getClass();
            if ( !clazz.isAnnotationPresent( Controller.class ) ) {
                continue;
            }

            String baseUrl = "";
            //获取Controller的url配置
            if ( clazz.isAnnotationPresent( RequestMapping.class ) ) {
                RequestMapping requestMapping = clazz.getAnnotation( RequestMapping.class );
                baseUrl = requestMapping.value();
            }

            //获取Method的url配置
            Method[] methods = clazz.getMethods();
            for ( Method method : methods ) {

                //没有加RequestMapping注解的直接忽略
                if ( !method.isAnnotationPresent( RequestMapping.class ) ) {
                    continue;
                }

                //映射URL
                RequestMapping requestMapping = method.getAnnotation( RequestMapping.class );
                // 拼装URL，并替换掉多余的/
                String url = ( "/" + baseUrl + "/" + requestMapping.value() ).replaceAll( "/+", "/" );
                Pattern pattern = Pattern.compile( url );
                handlerMapping.add( new Handler( pattern, entry.getValue(), method ) );
                System.out.println( "Mapped " + url + "," + method );
            }
        }
    }

    /**
     * 4.完成依赖注入
     */
    private void doAutowired() {
        // 为什么要先循环一遍mapping，再单独循环一遍
        // 因为可能在设置Autowried的时候，可能对应的对象还没有被初始化，导致报错。
        for ( Object object : ioc.values() ) {
            // 得到该类中所有成员
            final Field[] fields = object.getClass().getDeclaredFields();
            for ( Field field : fields ) {
                // 处理所有带有Autowired的成员变量，完成自动注入
                if ( field.isAnnotationPresent( Autowired.class ) ) {
                    Autowired autowired = field.getAnnotation( Autowired.class );
                    // 从注解中取出用户自定义的成员变量的别名
                    String beanName = autowired.value().trim();
                    if ( "".equals( beanName ) ) {
                        // 如果没有取到用户自定义的变量名，则设置为变量的类型
                        beanName = toLowerFirstCase( field.getType().getSimpleName() );
                    }
                    // 处理私有成员变量
                    field.setAccessible( true );
                    try {
                        // 在mapping中找到对应实例，注入到该成员变量中
                        field.set( object, ioc.get( beanName ) );
                    } catch ( IllegalAccessException e ) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 3.初始化所有相关类的实例，并放入到IOC容器中
     */
    private void doInstance() {
        for ( String className : classNames ) {
            if ( !className.contains( "." ) ) {
                continue;
            }
            try {
                Class< ? > clazz = Class.forName( className );
                String beanName = toLowerFirstCase( clazz.getSimpleName() );
                // 处理所有Controller类
                if ( clazz.isAnnotationPresent( Controller.class ) ) {
                    // 把满足类型为Controller的类进行实例化，并放入到mapping中
                    ioc.put( beanName, clazz.newInstance() );
                } else if ( clazz.isAnnotationPresent( Service.class ) ) {
                    //1、默认的类名首字母小写
                    Service service = clazz.getAnnotation( Service.class );
                    final Object instance = clazz.newInstance();
                    //2、如果用户自定义了名字，就用用户定义的名字
                    if ( !"".equals( service.value() ) ) {
                        beanName = service.value();
                        ioc.put( beanName, instance );
                        continue;
                    }

                    //3、如果没有定义名字，就按接口类型黄建实例
                    for ( Class< ? > i : clazz.getInterfaces() ) {
                        if ( ioc.containsKey( i.getName() ) ) {
                            throw new RuntimeException( "The beanName is exists!!" );
                        }
                        ioc.put( toLowerFirstCase( i.getSimpleName() ), instance );
                    }
                }
            } catch ( Exception e ) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 2.载入application.properties
     *
     * @param propertiesPath
     */
    private void doLoadConfig( String propertiesPath ) {
        try ( InputStream is = this.getClass().getClassLoader().getResourceAsStream( propertiesPath ) ) {
            configContext.load( is );
        } catch ( IOException e ) {
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase( String simpleName ) {
        char firstChar = simpleName.charAt( 0 );
        if ( Character.isLowerCase( firstChar ) ) {
            return simpleName;
        } else {
            return Character.toLowerCase( firstChar ) + simpleName.substring( 1 );
        }
    }

    /**
     * 1.扫描配置文件中指定的包路径下的类文件
     *
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
                    classNames.add( clazzName );
                }
            } );
        } catch ( URISyntaxException | IOException e ) {
            e.printStackTrace();
        }
    }

    private class Handler {
        private Object controller;
        private Method method;
        private Pattern pattern;
        private Map< String, Integer > paramIndexMapping;

        public Handler( Pattern pattern, Object controller, Method method ) {
            this.pattern = pattern;
            this.controller = controller;
            this.method = method;

            paramIndexMapping = new HashMap<>();
            putParamIndexMapping( method );
        }

        private void putParamIndexMapping( Method method ) {
            //获取方法的形参列表
            Class< ? >[] parameterTypes = method.getParameterTypes();

            //保存赋值参数的位置
            Object[] paramValues = new Object[ parameterTypes.length ];
            //根据参数位置动态赋值
            for ( int i = 0; i < parameterTypes.length; i++ ) {
                Class< ? > type = parameterTypes[ i ];
                if ( type == HttpServletRequest.class || type == HttpServletResponse.class ) {
                    paramIndexMapping.put( type.getName(), i );
                } else if ( type == String.class ) {
                    final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                    final Annotation[] annotations = parameterAnnotations[ i ];
                    for ( Annotation annotation : annotations ) {
                        if ( annotation instanceof RequestParam ) {
                            final String value = ( ( RequestParam ) annotation ).value();
                            // 必须要求RequestParam注解不能有默认值，否则如果用户不自定义名字，就会有问题
                            if ( !"".equals( value ) ) {
                                paramIndexMapping.put( value, i );
                            }
                        }
                    }
                }
            }
        }
    }
}
